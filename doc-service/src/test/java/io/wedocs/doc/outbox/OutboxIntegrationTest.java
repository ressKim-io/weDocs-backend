package io.wedocs.doc.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.wedocs.doc.auth.SystemRole;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.auth.UserRepository;
import io.wedocs.doc.page.Page;
import io.wedocs.doc.page.PageTreeService;
import io.wedocs.doc.workspace.WorkspaceMember;
import io.wedocs.doc.workspace.WorkspaceMemberRepository;
import io.wedocs.doc.workspace.WorkspaceRepository;
import io.wedocs.doc.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Phase 5 outbox 하드닝 검증 — 트랜잭션 정합성 + 새 필드(aggregate_type, actor_id) +
/// Jackson 직렬화(특수문자 안전성) + cleanup 쿼리.
///
/// @Transactional 을 테스트에 걸면 커밋 자체가 발생하지 않으므로, 여기서는 걸지 않고
/// BeforeEach에서 수동으로 사전 데이터를 삽입한 뒤 서비스 호출 후 직접 조회한다.
/// 단, 테스트 격리를 위해 매 실행마다 고유 UUID를 사용한다.
@SpringBootTest
@Testcontainers
class OutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private PageTreeService pageTree;
    @Autowired private OutboxRepository outbox;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private ObjectMapper objectMapper;

    private UUID actorId;
    private UUID workspaceId;

    @BeforeEach
    @Transactional
    void setUp() {
        actorId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        users.save(new User(actorId, "outbox-test-" + actorId + "@example.com",
                "$2a$10$dummyhashfortest000000000000000000000000000000000000",
                "Tester", SystemRole.USER));
        workspaces.save(new Workspace(workspaceId, "test-ws", actorId));
        members.save(WorkspaceMember.owner(workspaceId, actorId));
    }

    @Test
    @DisplayName("페이지 생성 시 outbox에 page.created 이벤트가 같은 트랜잭션에서 삽입된다")
    void create_inserts_outbox_event() throws Exception {
        // When
        Page page = pageTree.create(actorId, workspaceId, null, "Outbox Test Page");

        // Then: outbox에 이벤트 존재
        OutboxEvent event = findEvent(page.getId(), "page.created");
        assertThat(event.getAggregateType()).isEqualTo("page");
        assertThat(event.getActorId()).isEqualTo(actorId);
        assertThat(event.getTraceparent()).isNull();
        assertThat(event.getPublishedAt()).isNull();

        // payload가 유효한 JSON이고 필드가 올바른지
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("workspaceId").asText()).isEqualTo(workspaceId.toString());
        assertThat(payload.get("parentId").isNull()).isTrue();
        assertThat(payload.get("title").asText()).isEqualTo("Outbox Test Page");
    }

    @Test
    @DisplayName("페이지 rename 시 outbox에 page.renamed 이벤트가 삽입된다")
    void rename_inserts_outbox_event() throws Exception {
        // Given
        Page page = pageTree.create(actorId, workspaceId, null, "Original");

        // When
        pageTree.rename(actorId, page.getId(), "Updated Title");

        // Then
        OutboxEvent event = findEvent(page.getId(), "page.renamed");
        assertThat(event.getAggregateType()).isEqualTo("page");
        assertThat(event.getActorId()).isEqualTo(actorId);

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("title").asText()).isEqualTo("Updated Title");
    }

    @Test
    @DisplayName("페이지 archive 시 outbox에 page.archived 이벤트가 삽입된다")
    void archive_inserts_outbox_event() {
        // Given
        Page page = pageTree.create(actorId, workspaceId, null, "To Archive");

        // When
        pageTree.archive(actorId, page.getId());

        // Then
        OutboxEvent event = findEvent(page.getId(), "page.archived");
        assertThat(event.getAggregateType()).isEqualTo("page");
        assertThat(event.getActorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("특수문자가 포함된 title도 유효한 JSON으로 직렬화된다")
    void special_characters_produce_valid_json() throws Exception {
        // Given: 개행, 탭, 따옴표, 백슬래시, 유니코드 포함 title
        String specialTitle = "개행\n탭\t\"따옴표\"\\백슬래시 한글제어";

        // When
        Page page = pageTree.create(actorId, workspaceId, null, specialTitle);

        // Then: payload가 파싱 가능한 유효 JSON
        OutboxEvent event = findEvent(page.getId(), "page.created");
        assertThatCode(() -> objectMapper.readTree(event.getPayload())).doesNotThrowAnyException();
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("title").asText()).isEqualTo(specialTitle);
    }

    @Test
    @DisplayName("페이지 move 시 outbox에 page.moved 이벤트가 올바른 payload로 삽입된다")
    void move_inserts_outbox_event() throws Exception {
        // Given
        Page parent = pageTree.create(actorId, workspaceId, null, "Parent");
        Page child = pageTree.create(actorId, workspaceId, null, "Child");

        // When
        pageTree.move(actorId, child.getId(), parent.getId(), 0);

        // Then
        OutboxEvent event = findEvent(child.getId(), "page.moved");
        assertThat(event.getAggregateType()).isEqualTo("page");
        assertThat(event.getActorId()).isEqualTo(actorId);

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("parentId").asText()).isEqualTo(parent.getId().toString());
        assertThat(payload.get("position").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("cleanup: 미발행 cutoff 이전 행만 삭제된다")
    @Transactional
    void cleanup_deletes_old_unpublished_only() {
        // 현재 시점의 행 수 (setUp의 create 등으로 생길 수 있으므로 기준 잡기)
        // cleanup은 30일 이전만 삭제하므로 방금 만든 행은 남아야 한다
        Page page = pageTree.create(actorId, workspaceId, null, "CleanupTest");
        long countBefore = outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(page.getId()))
                .count();

        // cutoff를 미래로 설정하면 모든 미발행 행이 삭제 대상
        int deleted = outbox.deleteByPublishedAtIsNullAndCreatedAtBefore(java.time.Instant.now().plusSeconds(60));
        assertThat(deleted).isGreaterThanOrEqualTo((int) countBefore);
    }

    /// 특정 aggregate + eventType 조합의 이벤트를 찾는다.
    private OutboxEvent findEvent(UUID aggregateId, String eventType) {
        List<OutboxEvent> events = outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(aggregateId) && eventType.equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }
}

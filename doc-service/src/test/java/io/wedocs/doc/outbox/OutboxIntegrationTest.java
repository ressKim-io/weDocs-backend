package io.wedocs.doc.outbox;

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

/// Phase 5 outbox 트랜잭션 정합성 검증 — 페이지 변경과 outbox 행이 **같은 커밋**에서 나타나는지
/// Testcontainers Postgres로 실측한다. relay(M4)가 이 행을 읽어 Kafka에 발행할 수 있는 전제 조건이다.
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
    void create_inserts_outbox_event() {
        // When
        Page page = pageTree.create(actorId, workspaceId, null, "Outbox Test Page");

        // Then: outbox에 이벤트 존재
        List<OutboxEvent> events = outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(page.getId()))
                .toList();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo("page.created");
        assertThat(event.getPayload()).contains(workspaceId.toString());
        assertThat(event.getPayload()).contains("Outbox Test Page");
        // M2: traceparent는 null(OTel 미설치), published_at도 null(relay 미구현)
        assertThat(event.getTraceparent()).isNull();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("페이지 rename 시 outbox에 page.renamed 이벤트가 삽입된다")
    void rename_inserts_outbox_event() {
        // Given
        Page page = pageTree.create(actorId, workspaceId, null, "Original");

        // When
        pageTree.rename(actorId, page.getId(), "Updated Title");

        // Then
        List<OutboxEvent> events = outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(page.getId()) && "page.renamed".equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getPayload()).contains("Updated Title");
    }

    @Test
    @DisplayName("페이지 archive 시 outbox에 page.archived 이벤트가 삽입된다")
    void archive_inserts_outbox_event() {
        // Given
        Page page = pageTree.create(actorId, workspaceId, null, "To Archive");

        // When
        pageTree.archive(actorId, page.getId());

        // Then
        List<OutboxEvent> events = outbox.findAll().stream()
                .filter(e -> e.getAggregateId().equals(page.getId()) && "page.archived".equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
    }
}

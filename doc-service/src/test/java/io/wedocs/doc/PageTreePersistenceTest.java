package io.wedocs.doc;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PageSnapshot;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.PageSnapshotRepository;
import io.wedocs.doc.repository.UserRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Phase 1a 데이터 레이어 검증 — 실제 Postgres(Testcontainers)에서:
/// (1) Flyway 마이그레이션 + JPA ddl-auto=validate 통과(컨텍스트 로드 = 스키마/매핑 정합),
/// (2) page-tree 자기참조 저장/조회, (3) page_snapshots 페이지당 최신 1행 UPSERT(ADR-0013).
/// @Transactional 로 각 테스트 롤백 → 순서 비의존(testing.md).
@SpringBootTest
@Testcontainers
@Transactional
class PageTreePersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PageRepository pages;
    @Autowired private PageSnapshotRepository snapshots;

    @Test
    @DisplayName("Flyway 마이그레이션 + JPA validate 통과 시 컨텍스트가 로드되고 테이블이 비어있다")
    void contextLoads_withMigratedSchema() {
        // Given/When: 컨텍스트 로드(=Flyway 7테이블 생성 + ddl-auto=validate 매핑 검증)
        // Then: 빈 스키마
        assertThat(pages.count()).isZero();
        assertThat(snapshots.count()).isZero();
    }

    @Test
    @DisplayName("부모-자식 페이지를 저장하면 자기참조 트리(parent_id)가 보존된다")
    void persistPageTree_keepsParentReference() {
        // Given: user + workspace
        User owner = users.save(new User(UUID.randomUUID(), "alice@wedocs.io", "hash", "Alice"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "Engineering", owner.getId()));

        // When: 루트 페이지 + 자식 페이지(parent=root)
        Page root = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), null, "Root", 0, false));
        Page child = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), root.getId(), "Child", 0, false));

        // Then: 자식은 root를 부모로, 루트는 부모 없음
        Page reloadedChild = pages.findById(child.getId()).orElseThrow();
        assertThat(reloadedChild.getParentId()).isEqualTo(root.getId());
        assertThat(pages.findById(root.getId()).orElseThrow().getParentId()).isNull();
        assertThat(pages.findByParentId(root.getId())).extracting(Page::getId).containsExactly(child.getId());
    }

    @Test
    @DisplayName("같은 page_id로 스냅샷을 두 번 저장하면 최신 1행만 남는다 (UPSERT, ADR-0013)")
    void saveSnapshot_upsertsLatestSingleRow() {
        // Given: 페이지 1개
        User owner = users.save(new User(UUID.randomUUID(), "bob@wedocs.io", "hash", "Bob"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "W", owner.getId()));
        Page page = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), null, "P", 0, false));

        // When: v1 저장 후 같은 page_id로 v2 저장(UPSERT)
        snapshots.saveAndFlush(new PageSnapshot(page.getId(), new byte[]{1, 2, 3}, 1L));
        snapshots.saveAndFlush(new PageSnapshot(page.getId(), new byte[]{4, 5, 6, 7}, 2L));

        // Then: 페이지당 1행, 최신 버전/내용
        assertThat(snapshots.count()).isEqualTo(1);
        PageSnapshot latest = snapshots.findById(page.getId()).orElseThrow();
        assertThat(latest.getVersion()).isEqualTo(2L);
        assertThat(latest.getSnapshot()).containsExactly(4, 5, 6, 7);
    }
}

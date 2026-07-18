package io.wedocs.doc;

import io.wedocs.doc.page.Page;
import io.wedocs.doc.snapshot.PageSnapshot;
import io.wedocs.doc.auth.SystemRole;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.page.PageRepository;
import io.wedocs.doc.snapshot.PageSnapshotRepository;
import io.wedocs.doc.auth.UserRepository;
import io.wedocs.doc.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Phase 1a 데이터 레이어 검증 — 실제 Postgres(Testcontainers)에서:
/// (1) Flyway 마이그레이션 + JPA ddl-auto=validate 통과(컨텍스트 로드 = 스키마/매핑 정합),
/// (2) page-tree 자기참조 저장/조회, (3) page_snapshots 페이지당 최신 1행 UPSERT(ADR-0013),
/// (4) 감사 시각(created/updated) Auditing, (5) system_role 매핑·CHECK 제약(ADR-0016).
/// @Transactional 로 각 테스트 롤백 → 순서 비의존(testing.md).
@SpringBootTest
@Testcontainers
@Transactional
class PageTreePersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PageRepository pages;
    @Autowired private PageSnapshotRepository snapshots;
    @PersistenceContext private EntityManager em;

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

    @Test
    @DisplayName("저장 시 created_at/updated_at이 채워지고, 수정하면 updated_at이 갱신된다 (Auditing)")
    void auditTimestamps_populatedOnSave_andBumpedOnUpdate() {
        // Given: 페이지 1개 저장
        User owner = users.save(new User(UUID.randomUUID(), "carol@wedocs.io", "hash", "Carol"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "W", owner.getId()));
        Page page = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), null, "Old", 0, false));
        Instant createdOnInsert = page.getCreatedAt();
        Instant updatedOnInsert = page.getUpdatedAt();

        // When: 제목 수정 후 재저장
        page.rename("New");
        pages.saveAndFlush(page);

        // Then: created_at 불변(updatable=false), updated_at은 재기록(박제 아님)
        assertThat(createdOnInsert).isNotNull();
        assertThat(updatedOnInsert).isNotNull();
        Page reloaded = pages.findById(page.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("New");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdOnInsert);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(updatedOnInsert);
    }

    @Test
    @DisplayName("system_role 기본값은 user이고, system_admin이 DB 왕복으로 매핑된다 (ADR-0016)")
    void systemRole_defaultsToUser_andRoundTripsAdmin() {
        // Given: 기본 생성자 user + 명시 admin
        User normal = users.saveAndFlush(new User(UUID.randomUUID(), "dan@wedocs.io", "hash", "Dan"));
        User admin = users.saveAndFlush(
                new User(UUID.randomUUID(), "root@wedocs.io", "hash", "Root", SystemRole.SYSTEM_ADMIN));

        // When: 영속성 컨텍스트 비우고 DB에서 재조회(컨버터 왕복 강제)
        em.flush();
        em.clear();

        // Then: 기본값 user, admin 왕복(소문자 'system_admin' 저장 + CHECK 통과 = saveAndFlush 성공이 증명)
        assertThat(users.findById(normal.getId()).orElseThrow().getSystemRole()).isEqualTo(SystemRole.USER);
        assertThat(users.findById(admin.getId()).orElseThrow().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    }

    @Test
    @DisplayName("system_role CHECK 제약은 정의되지 않은 값을 거부한다 (ADR-0016)")
    void systemRoleCheck_rejectsUnknownValue() {
        // When/Then: enum에 없는 'root' 직접 INSERT → DB CHECK 위반
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    insert into users (id, email, password_hash, display_name, system_role)
                    values (?1, 'mallory@wedocs.io', 'hash', 'Mallory', 'root')
                    """)
                    .setParameter(1, UUID.randomUUID())
                    .executeUpdate();
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }
}

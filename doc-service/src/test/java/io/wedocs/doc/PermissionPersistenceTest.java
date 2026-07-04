package io.wedocs.doc;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PagePermission;
import io.wedocs.doc.domain.PagePermissionLevel;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;
import io.wedocs.doc.repository.PagePermissionRepository;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.UserRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// M2 1b — WorkspaceMember/PagePermission 복합키(@EmbeddedId) 영속화 검증.
/// 이 코드베이스 최초의 복합 PK 패턴이라 실 스키마(V1__init_page_tree.sql)에 대고 가장 먼저 확인한다.
/// @Transactional 로 각 테스트 롤백 → 순서 비의존(testing.md).
@SpringBootTest
@Testcontainers
@Transactional
class PermissionPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PageRepository pages;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private PagePermissionRepository pagePermissions;
    @PersistenceContext private EntityManager em;

    @Test
    @DisplayName("워크스페이스 멤버십을 저장하면 복합키(workspace_id, user_id)로 조회된다")
    void persistWorkspaceMember_findableByCompositeKey() {
        // Given: user + workspace
        User owner = users.save(new User(UUID.randomUUID(), "alice@wedocs.io", "hash", "Alice"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "Engineering", owner.getId()));

        // When: 멤버십 저장(member 레벨)
        workspaceMembers.saveAndFlush(new WorkspaceMember(ws.getId(), owner.getId(), WorkspaceRole.MEMBER));

        // Then: 복합키로 조회되고 role 보존
        WorkspaceMember found = workspaceMembers
                .findById_WorkspaceIdAndId_UserId(ws.getId(), owner.getId())
                .orElseThrow();
        assertThat(found.getRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(found.getWorkspaceId()).isEqualTo(ws.getId());
        assertThat(found.getUserId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("페이지별 공유 권한을 저장하면 복합키(page_id, user_id)로 조회된다")
    void persistPagePermission_findableByCompositeKey() {
        // Given: user + workspace + page
        User owner = users.save(new User(UUID.randomUUID(), "bob@wedocs.io", "hash", "Bob"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "W", owner.getId()));
        Page page = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), null, "P", 0, false));
        User grantee = users.save(new User(UUID.randomUUID(), "carol@wedocs.io", "hash", "Carol"));

        // When: 페이지별 editor 공유 저장
        pagePermissions.saveAndFlush(new PagePermission(page.getId(), grantee.getId(), PagePermissionLevel.EDITOR));

        // Then: 복합키로 조회되고 level 보존
        PagePermission found = pagePermissions
                .findById_PageIdAndId_UserId(page.getId(), grantee.getId())
                .orElseThrow();
        assertThat(found.getLevel()).isEqualTo(PagePermissionLevel.EDITOR);
        assertThat(found.getPageId()).isEqualTo(page.getId());
        assertThat(found.getUserId()).isEqualTo(grantee.getId());
    }

    @Test
    @DisplayName("workspace_members.role CHECK 제약은 정의되지 않은 값을 거부한다")
    void workspaceMemberRoleCheck_rejectsUnknownValue() {
        // Given: user + workspace
        User owner = users.save(new User(UUID.randomUUID(), "dan@wedocs.io", "hash", "Dan"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "W", owner.getId()));

        // When/Then: enum에 없는 'root' 직접 INSERT → DB CHECK 위반
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    insert into workspace_members (workspace_id, user_id, role)
                    values (?1, ?2, 'root')
                    """)
                    .setParameter(1, ws.getId())
                    .setParameter(2, owner.getId())
                    .executeUpdate();
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("page_permissions.level CHECK 제약은 정의되지 않은 값을 거부한다")
    void pagePermissionLevelCheck_rejectsUnknownValue() {
        // Given: user + workspace + page
        User owner = users.save(new User(UUID.randomUUID(), "erin@wedocs.io", "hash", "Erin"));
        Workspace ws = workspaces.save(new Workspace(UUID.randomUUID(), "W", owner.getId()));
        Page page = pages.saveAndFlush(new Page(UUID.randomUUID(), ws.getId(), null, "P", 0, false));

        // When/Then: enum에 없는 'commenter' 직접 INSERT → DB CHECK 위반
        assertThatThrownBy(() -> {
            em.createNativeQuery("""
                    insert into page_permissions (page_id, user_id, level)
                    values (?1, ?2, 'commenter')
                    """)
                    .setParameter(1, page.getId())
                    .setParameter(2, owner.getId())
                    .executeUpdate();
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }
}

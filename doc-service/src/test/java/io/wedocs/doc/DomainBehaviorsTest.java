package io.wedocs.doc;

import io.wedocs.doc.page.Page;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.workspace.WorkspaceMember;
import io.wedocs.doc.workspace.WorkspaceRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// 도메인 행위 — 정적 팩토리(관문 규약)와 트리 조작(moveTo/archive)의 순수 단위 테스트.
class DomainBehaviorsTest {

    @Nested
    @DisplayName("Workspace.create")
    class WorkspaceCreate {

        @Test
        @DisplayName("id를 자동 생성하고 이름 공백을 정리한다")
        void generatesIdAndStripsName() {
            // Given
            UUID ownerId = UUID.randomUUID();

            // When
            Workspace workspace = Workspace.create("  팀 위키  ", ownerId);

            // Then
            assertThat(workspace.getId()).isNotNull();
            assertThat(workspace.getName()).isEqualTo("팀 위키");
            assertThat(workspace.getOwnerId()).isEqualTo(ownerId);
        }
    }

    @Nested
    @DisplayName("WorkspaceMember 정적 팩토리")
    class WorkspaceMemberFactory {

        @Test
        @DisplayName("owner() — OWNER 역할을 부여한다")
        void owner_setsOwnerRole() {
            // Given
            UUID workspaceId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            // When
            WorkspaceMember member = WorkspaceMember.owner(workspaceId, userId);

            // Then
            assertThat(member.getRole()).isEqualTo(WorkspaceRole.OWNER);
        }

        @Test
        @DisplayName("member() — MEMBER 역할을 부여한다")
        void member_setsMemberRole() {
            // Given
            UUID workspaceId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            // When
            WorkspaceMember member = WorkspaceMember.member(workspaceId, userId);

            // Then
            assertThat(member.getRole()).isEqualTo(WorkspaceRole.MEMBER);
        }
    }

    @Nested
    @DisplayName("Page.create")
    class PageCreate {

        @Test
        @DisplayName("루트 페이지 — id 생성, parentId=null, position=0, archived=false")
        void rootPage_generatesIdWithDefaults() {
            // Given
            UUID workspaceId = UUID.randomUUID();

            // When
            Page root = Page.create(workspaceId, null, "루트");

            // Then
            assertThat(root.getId()).isNotNull();
            assertThat(root.getParentId()).isNull();
            assertThat(root.getPosition()).isZero();
            assertThat(root.isArchived()).isFalse();
        }

        @Test
        @DisplayName("자식 페이지 — 지정된 parentId를 보존한다")
        void childPage_preservesParentId() {
            // Given
            UUID workspaceId = UUID.randomUUID();
            UUID parentId = UUID.randomUUID();

            // When
            Page child = Page.create(workspaceId, parentId, "자식");

            // Then
            assertThat(child.getParentId()).isEqualTo(parentId);
        }
    }

    @Nested
    @DisplayName("Page.moveTo")
    class PageMoveTo {

        @Test
        @DisplayName("다른 부모 아래로 이동 — parentId와 position을 함께 갱신한다")
        void movesToNewParent() {
            // Given
            Page page = Page.create(UUID.randomUUID(), UUID.randomUUID(), "이동 대상");
            UUID newParentId = UUID.randomUUID();

            // When
            page.moveTo(newParentId, 3);

            // Then
            assertThat(page.getParentId()).isEqualTo(newParentId);
            assertThat(page.getPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("루트로 이동 — parentId를 null로 설정한다")
        void movesToRoot() {
            // Given
            Page page = Page.create(UUID.randomUUID(), UUID.randomUUID(), "이동 대상");

            // When
            page.moveTo(null, 0);

            // Then
            assertThat(page.getParentId()).isNull();
            assertThat(page.getPosition()).isZero();
        }
    }

    @Nested
    @DisplayName("Page.archive")
    class PageArchive {

        @Test
        @DisplayName("가역 숨김 플래그만 세운다(영구삭제 아님, D-4)")
        void setsArchivedFlag() {
            // Given
            Page page = Page.create(UUID.randomUUID(), null, "숨길 페이지");

            // When
            page.archive();

            // Then
            assertThat(page.isArchived()).isTrue();
        }
    }
}

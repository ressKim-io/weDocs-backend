package io.wedocs.doc;

import io.wedocs.doc.page.Page;
import io.wedocs.doc.auth.User;
import io.wedocs.doc.workspace.Workspace;
import io.wedocs.doc.workspace.WorkspaceMember;
import io.wedocs.doc.workspace.WorkspaceRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// 1c PR② 도메인 행위 — 정적 팩토리(관문 규약)와 트리 조작(moveTo/archive)의 순수 단위 테스트.
class DomainBehaviorsTest {

    @Test
    @DisplayName("Workspace.create — id 생성 + 이름 공백 정리(User.register와 동일 규약)")
    void workspaceCreate_generatesIdAndStripsName() {
        UUID ownerId = UUID.randomUUID();

        Workspace workspace = Workspace.create("  팀 위키  ", ownerId);

        assertThat(workspace.getId()).isNotNull();
        assertThat(workspace.getName()).isEqualTo("팀 위키");
        assertThat(workspace.getOwnerId()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("WorkspaceMember.owner / member — 정적 팩토리가 role을 소유")
    void workspaceMemberFactories_setRole() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThat(WorkspaceMember.owner(workspaceId, userId).getRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(WorkspaceMember.member(workspaceId, userId).getRole()).isEqualTo(WorkspaceRole.MEMBER);
    }

    @Test
    @DisplayName("Page.create — id 생성, position 0, 비아카이브로 시작")
    void pageCreate_generatesIdWithDefaults() {
        UUID workspaceId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        Page root = Page.create(workspaceId, null, "루트");
        Page child = Page.create(workspaceId, parentId, "자식");

        assertThat(root.getId()).isNotNull();
        assertThat(root.getParentId()).isNull();
        assertThat(root.getPosition()).isZero();
        assertThat(root.isArchived()).isFalse();
        assertThat(child.getParentId()).isEqualTo(parentId);
    }

    @Test
    @DisplayName("Page.moveTo — parent와 position을 함께 갱신 (루트 이동 = null parent)")
    void pageMoveTo_updatesParentAndPosition() {
        Page page = Page.create(UUID.randomUUID(), UUID.randomUUID(), "이동 대상");
        UUID newParentId = UUID.randomUUID();

        page.moveTo(newParentId, 3);
        assertThat(page.getParentId()).isEqualTo(newParentId);
        assertThat(page.getPosition()).isEqualTo(3);

        page.moveTo(null, 0);
        assertThat(page.getParentId()).isNull();
        assertThat(page.getPosition()).isZero();
    }

    @Test
    @DisplayName("Page.archive — 가역 숨김 플래그만 세운다(영구삭제 아님, D-4)")
    void pageArchive_setsFlag() {
        Page page = Page.create(UUID.randomUUID(), null, "숨길 페이지");

        page.archive();

        assertThat(page.isArchived()).isTrue();
    }
}

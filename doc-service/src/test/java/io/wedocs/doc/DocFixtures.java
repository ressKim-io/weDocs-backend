package io.wedocs.doc;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PagePermission;
import io.wedocs.doc.domain.PagePermissionLevel;
import io.wedocs.doc.domain.User;
import io.wedocs.doc.domain.Workspace;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;

import java.util.UUID;

/// 테스트 전용 객체 생성 유틸(testing.md Fixture 패턴) — 저장은 각 테스트가 자신의 리포지토리로 수행한다.
public final class DocFixtures {

    private DocFixtures() {
    }

    public static User user(String email) {
        return new User(UUID.randomUUID(), email, "hash", email);
    }

    public static Workspace workspace(String name, UUID ownerId) {
        return new Workspace(UUID.randomUUID(), name, ownerId);
    }

    public static Page rootPage(UUID workspaceId, String title) {
        return new Page(UUID.randomUUID(), workspaceId, null, title, 0, false);
    }

    public static Page childPage(UUID workspaceId, UUID parentId, String title) {
        return new Page(UUID.randomUUID(), workspaceId, parentId, title, 0, false);
    }

    public static WorkspaceMember member(UUID workspaceId, UUID userId, WorkspaceRole role) {
        return new WorkspaceMember(workspaceId, userId, role);
    }

    public static PagePermission permission(UUID pageId, UUID userId, PagePermissionLevel level) {
        return new PagePermission(pageId, userId, level);
    }
}

package io.wedocs.doc.workspace;


import java.util.UUID;

public record MemberResponse(UUID workspaceId, UUID userId, WorkspaceRole role) {

    public static MemberResponse from(WorkspaceMember member) {
        return new MemberResponse(member.getWorkspaceId(), member.getUserId(), member.getRole());
    }
}

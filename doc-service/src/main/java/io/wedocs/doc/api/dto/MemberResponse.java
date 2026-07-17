package io.wedocs.doc.api.dto;

import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;

import java.util.UUID;

public record MemberResponse(UUID workspaceId, UUID userId, WorkspaceRole role) {

    public static MemberResponse from(WorkspaceMember member) {
        return new MemberResponse(member.getWorkspaceId(), member.getUserId(), member.getRole());
    }
}

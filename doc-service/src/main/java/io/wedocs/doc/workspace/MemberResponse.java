package io.wedocs.doc.workspace;


import java.util.UUID;

/// 워크스페이스 멤버 정보 응답 DTO.
public record MemberResponse(UUID workspaceId, UUID userId, WorkspaceRole role) {

    public static MemberResponse from(WorkspaceMember member) {
        return new MemberResponse(member.getWorkspaceId(), member.getUserId(), member.getRole());
    }
}

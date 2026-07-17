package io.wedocs.doc.service;

import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.ForbiddenException;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceRole;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/// REST 워크스페이스 인가 관문 — 비멤버는 미존재 워크스페이스와 같은 404로 붕괴(존재 비노출, P3),
/// 멤버인데 owner 권한이 필요하면 403(멤버는 존재를 이미 안다).
@RequiredArgsConstructor
@Component
public class WorkspaceAccessGuard {

    private final WorkspaceMemberRepository members;

    public WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
        return members.findById_WorkspaceIdAndId_UserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException(DocErrorCode.WORKSPACE_NOT_FOUND));
    }

    public WorkspaceMember requireOwner(UUID workspaceId, UUID userId) {
        WorkspaceMember member = requireMember(workspaceId, userId);
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new ForbiddenException();
        }
        return member;
    }
}

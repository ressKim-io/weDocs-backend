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
/// 호출자가 404 코드를 지정하는 3-arg requireOwner는 다른 feature(예: 페이지 공유 관리)의 존재 비노출에도 재사용된다.
@RequiredArgsConstructor
@Component
public class WorkspaceAccessGuard {

    private final WorkspaceMemberRepository members;

    public WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
        return loadMember(workspaceId, userId, DocErrorCode.WORKSPACE_NOT_FOUND);
    }

    public WorkspaceMember requireOwner(UUID workspaceId, UUID userId) {
        return requireOwner(workspaceId, userId, DocErrorCode.WORKSPACE_NOT_FOUND);
    }

    /// 숨겨야 할 리소스가 워크스페이스가 아닌 경우(예: 페이지 공유 관리) 호출자가 404 코드를 지정한다 —
    /// 미존재와 비멤버를 같은 코드로 붕괴시켜 존재 비노출을 유지(secure-coding P3 IDOR).
    public WorkspaceMember requireOwner(UUID workspaceId, UUID userId, DocErrorCode notFoundCode) {
        WorkspaceMember member = loadMember(workspaceId, userId, notFoundCode);
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new ForbiddenException();
        }
        return member;
    }

    private WorkspaceMember loadMember(UUID workspaceId, UUID userId, DocErrorCode notFoundCode) {
        return members.findById_WorkspaceIdAndId_UserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException(notFoundCode));
    }
}

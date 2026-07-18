package io.wedocs.doc.page;

import io.wedocs.doc.workspace.WorkspaceAccessGuard;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.NotFoundException;
import io.wedocs.doc.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/// 페이지 공유 부여·회수(PRD §4.2 오버라이드+상속, §4.3 관리=owner 전용, J4).
/// 대상은 존재 유저면 충분 — 워크스페이스 비멤버 공유 허용(명시 권한이 해석 1순위).
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class PageSharingService {

    private final PageRepository pages;
    private final WorkspaceAccessGuard workspaceAccess;
    private final UserRepository users;
    private final PagePermissionRepository pagePermissions;

    /// PUT 멱등 — 복합키 save = UPSERT(재공유는 level 교체). 동시 재공유는 last-write-wins 수용.
    @Transactional
    public void grant(UUID actorId, UUID pageId, UUID targetUserId, PagePermissionLevel level) {
        requireSharableBy(actorId, pageId);
        if (!users.existsById(targetUserId)) {
            throw new NotFoundException(DocErrorCode.USER_NOT_FOUND);
        }
        pagePermissions.save(new PagePermission(pageId, targetUserId, level));
    }

    /// DELETE 멱등 — 없는 권한 회수도 성공(204). 회수 반영은 재연결 시(PRD §5: 연결 중 강등 비범위).
    @Transactional
    public void revoke(UUID actorId, UUID pageId, UUID targetUserId) {
        requireSharableBy(actorId, pageId);
        pagePermissions.deleteById(new PagePermissionId(pageId, targetUserId));
    }

    /// 공유 관리 자격: 그 페이지 워크스페이스의 owner. 미존재 페이지·비멤버·공유받은 게스트를 모두
    /// 같은 page-not-found로 붕괴시켜 존재 비노출(IDOR) — 멤버인데 owner 아님만 403.
    /// 소유 feature(workspace)의 공개 API(WorkspaceAccessGuard)를 경유해 멤버십 조회를 재사용한다
    /// (교차 feature repository 직접 주입 지양, layering-readability P7). 404 코드만 page로 지정.
    private void requireSharableBy(UUID actorId, UUID pageId) {
        Page page = pages.findById(pageId).orElseThrow(() -> new NotFoundException(DocErrorCode.PAGE_NOT_FOUND));
        workspaceAccess.requireOwner(page.getWorkspaceId(), actorId, DocErrorCode.PAGE_NOT_FOUND);
    }
}

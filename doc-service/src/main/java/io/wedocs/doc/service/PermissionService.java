package io.wedocs.doc.service;

import io.wedocs.doc.domain.Page;
import io.wedocs.doc.domain.PagePermission;
import io.wedocs.doc.domain.PagePermissionLevel;
import io.wedocs.doc.repository.PagePermissionRepository;
import io.wedocs.doc.repository.PageRepository;
import io.wedocs.doc.repository.WorkspaceMemberRepository;
import io.wedocs.doc.service.EffectivePermission.EffectiveRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/// PRD §4.2 유효 권한 해석: 명시 PagePermission > 조상 상속 > workspace baseline > 거부.
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class PermissionService {

    // D-3(PRD §4.1): 워크스페이스 member 기본 레벨 = editor. 상수로 명명해 향후 viewer 전환을 1줄로.
    private static final PagePermissionLevel DEFAULT_MEMBER_LEVEL = PagePermissionLevel.EDITOR;

    private final PageRepository pages;
    private final WorkspaceMemberRepository workspaceMembers;
    private final PagePermissionRepository pagePermissions;

    public EffectivePermission resolve(UUID pageId, UUID userId) {
        Page page = pages.findById(pageId).orElse(null);
        if (page == null) {
            return EffectivePermission.DENIED; // 존재 여부 비노출 — CheckPermission은 "없음"도 거부와 동일 응답
        }

        AncestorWalkResult walk = findNearestExplicitLevel(page, userId);
        if (walk.explicitLevel() != null) {
            return EffectivePermission.granted(toEffectiveRole(walk.explicitLevel()));
        }
        if (walk.depthCapReached()) {
            // 방어적 상한 도달 = 탐색이 잘려 "이 위에 명시 권한이 있는지 알 수 없는" 상태다.
            // baseline으로 승격시키면, 캡 위쪽의 명시적 강등(예: viewer 오버라이드)을 놓치고
            // over-grant할 위험이 있다 — 알 수 없으면 fail-closed로 거부한다(P3).
            return EffectivePermission.DENIED;
        }

        return workspaceMembers.findById_WorkspaceIdAndId_UserId(page.getWorkspaceId(), userId)
                .map(member -> switch (member.getRole()) {
                    case OWNER -> EffectivePermission.granted(EffectiveRole.OWNER);
                    case MEMBER -> EffectivePermission.granted(toEffectiveRole(DEFAULT_MEMBER_LEVEL));
                })
                .orElse(EffectivePermission.DENIED); // 워크스페이스 멤버 아님
    }

    /// 자기 자신 → 부모 → 조상... 순서로 가장 가까운 명시적 PagePermission을 찾는다.
    /// 루트에 정상 도달(명시 권한 없음)과 방어적 상한 도달(사이클 등 오염 데이터로 탐색이
    /// 잘림)은 서로 다른 결과다 — 전자만 baseline 폴백이 안전하다.
    private AncestorWalkResult findNearestExplicitLevel(Page startPage, UUID userId) {
        Page cursor = startPage;
        for (int hop = 0; cursor != null && hop < Page.MAX_ANCESTOR_DEPTH; hop++) {
            PagePermissionLevel explicit = pagePermissions
                    .findById_PageIdAndId_UserId(cursor.getId(), userId)
                    .map(PagePermission::getLevel)
                    .orElse(null);
            if (explicit != null) {
                return AncestorWalkResult.found(explicit);
            }
            UUID parentId = cursor.getParentId();
            if (parentId == null) {
                return AncestorWalkResult.ROOT_REACHED; // 루트 도달(정상 종료), 명시 권한 없음
            }
            cursor = pages.findById(parentId).orElse(null);
        }
        return AncestorWalkResult.DEPTH_CAP_REACHED; // 상한 도달 — 오염 데이터 가능성, 알 수 없음
    }

    private static EffectiveRole toEffectiveRole(PagePermissionLevel level) {
        return switch (level) {
            case VIEWER -> EffectiveRole.VIEWER;
            case EDITOR -> EffectiveRole.EDITOR;
        };
    }

    private record AncestorWalkResult(PagePermissionLevel explicitLevel, boolean depthCapReached) {
        static final AncestorWalkResult ROOT_REACHED = new AncestorWalkResult(null, false);
        static final AncestorWalkResult DEPTH_CAP_REACHED = new AncestorWalkResult(null, true);

        static AncestorWalkResult found(PagePermissionLevel level) {
            return new AncestorWalkResult(level, false);
        }
    }
}

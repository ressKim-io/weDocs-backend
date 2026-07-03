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

import java.util.Optional;
import java.util.UUID;

/// PRD §4.2 유효 권한 해석: 명시 PagePermission > 조상 상속 > workspace baseline > 거부.
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class PermissionService {

    // 정상 위키 트리 깊이(수십 레벨 이내)를 넉넉히 초과하는 방어적 상한.
    // ADR-0012가 move() 시 사이클 검사를 불변식으로 두지만, 데이터 오염/미래 버그에 대한
    // 안전망으로 둔다(secure-coding.md P2) — 정상 경로에서는 도달 불가.
    private static final int MAX_ANCESTOR_DEPTH = 64;

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

        Optional<PagePermissionLevel> explicit = findNearestExplicitLevel(page, userId);
        if (explicit.isPresent()) {
            return EffectivePermission.granted(toEffectiveRole(explicit.get()));
        }

        return workspaceMembers.findById_WorkspaceIdAndId_UserId(page.getWorkspaceId(), userId)
                .map(member -> switch (member.getRole()) {
                    case OWNER -> EffectivePermission.granted(EffectiveRole.OWNER);
                    case MEMBER -> EffectivePermission.granted(toEffectiveRole(DEFAULT_MEMBER_LEVEL));
                })
                .orElse(EffectivePermission.DENIED); // 워크스페이스 멤버 아님
    }

    /// 자기 자신 → 부모 → 조상... 순서로 가장 가까운 명시적 PagePermission을 찾는다.
    /// MAX_ANCESTOR_DEPTH 캡은 정상 경로가 아니라 사이클 등 오염 데이터에서 무한루프를 막는 안전망.
    private Optional<PagePermissionLevel> findNearestExplicitLevel(Page startPage, UUID userId) {
        Page cursor = startPage;
        for (int hop = 0; cursor != null && hop < MAX_ANCESTOR_DEPTH; hop++) {
            Optional<PagePermissionLevel> explicit = pagePermissions
                    .findById_PageIdAndId_UserId(cursor.getId(), userId)
                    .map(PagePermission::getLevel);
            if (explicit.isPresent()) {
                return explicit;
            }
            UUID parentId = cursor.getParentId();
            if (parentId == null) {
                return Optional.empty(); // 루트 도달, 명시 권한 없음
            }
            cursor = pages.findById(parentId).orElse(null);
        }
        return Optional.empty(); // 상한 도달 — 오염 데이터 방어적 폴백(워크스페이스 baseline으로)
    }

    private static EffectiveRole toEffectiveRole(PagePermissionLevel level) {
        return switch (level) {
            case VIEWER -> EffectiveRole.VIEWER;
            case EDITOR -> EffectiveRole.EDITOR;
        };
    }
}

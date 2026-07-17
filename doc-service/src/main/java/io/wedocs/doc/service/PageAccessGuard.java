package io.wedocs.doc.service;

import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.ForbiddenException;
import io.wedocs.doc.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/// REST 페이지 인가 관문 — 유효 권한 해석은 PermissionService(단일 소유)에 위임하고,
/// 여기서는 "부족하면 어떤 실패인가"만 결정한다: no-read=404(존재 비노출, P3 IDOR),
/// read-but-no-edit=403(읽기를 통과한 요청자는 존재를 이미 안다).
@RequiredArgsConstructor
@Component
public class PageAccessGuard {

    private final PermissionService permissions;

    public EffectivePermission requireRead(UUID pageId, UUID userId) {
        EffectivePermission permission = permissions.resolve(pageId, userId);
        if (!permission.canRead()) {
            throw new NotFoundException(DocErrorCode.PAGE_NOT_FOUND);
        }
        return permission;
    }

    public EffectivePermission requireEdit(UUID pageId, UUID userId) {
        EffectivePermission permission = requireRead(pageId, userId);
        if (!permission.canEdit()) {
            throw new ForbiddenException();
        }
        return permission;
    }
}

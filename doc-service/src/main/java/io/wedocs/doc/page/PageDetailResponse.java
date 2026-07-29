package io.wedocs.doc.page;

import io.wedocs.doc.common.error.InvariantViolationException;
import io.wedocs.doc.page.EffectivePermission.EffectiveRole;

import java.util.UUID;

/// 페이지 **단건 조회** 응답 — 구조 필드 + 호출자의 유효 권한.
///
/// 왜 목록(`PageResponse`)과 타입을 분리했나: 목록은 최대 `PageTreeService.MAX_PAGE_LIST`행이고 권한
/// 해석은 조상 walk라, 행마다 해석하면 N+1이 된다. 역할이 실제로 필요한 시점은 "이 페이지를 여는" 단건
/// 조회뿐이다. 그래서 목록에 nullable 역할 필드를 달아 한 타입이 두 계약을 겸하게 하지 않는다
/// (필드가 언제 채워지는지 계약만 보고는 알 수 없는 응답을 만들지 않는다).
public record PageDetailResponse(UUID id, UUID workspaceId, UUID parentId, String title,
                                 int position, boolean archived, Role myRole, boolean canEdit) {

    public static PageDetailResponse from(PageView view) {
        Page page = view.page();
        EffectivePermission permission = view.permission();
        return new PageDetailResponse(page.getId(), page.getWorkspaceId(), page.getParentId(),
                page.getTitle(), page.getPosition(), page.isArchived(),
                toRole(permission.role()), permission.canEdit());
    }

    /// `NONE`은 이 경로에 **도달할 수 없다** — `PageAccessGuard.requireRead`가 먼저 404로 막는다.
    /// 불변식이 깨지면 "권한 없음"을 200으로 조용히 흘리는 대신 즉시 실패시킨다(fail-closed).
    /// raw `IllegalStateException`이 아니라 카탈로그로 표현한다(error-handling P7) — 그래야 내부
    /// 상세가 구조화 로그로만 남고 클라이언트에는 고정 문구("unexpected error")가 나간다(P4).
    private static Role toRole(EffectiveRole role) {
        return switch (role) {
            case VIEWER -> Role.VIEWER;
            case EDITOR -> Role.EDITOR;
            case OWNER -> Role.OWNER;
            case NONE -> throw new InvariantViolationException(
                    "readable page resolved to NONE — requireRead invariant violated");
        };
    }

    /// 노출용 역할 — `EffectiveRole`에서 `NONE`을 뺀 것. 도메인 enum을 그대로 내보내면 계약에
    /// **절대 나타날 수 없는 값**이 포함돼, 클라이언트가 처리할 수 없는 분기를 강요받는다.
    ///
    /// ⚠️ 클라이언트는 이 값에서 **편집 가능 여부를 재유도하지 않는다.** "editor 또는 owner가 편집 가능"은
    /// 서버 정책이고, 그것을 클라이언트가 다시 구현하는 순간 두 곳이 갈라진다(역할이 추가되면 즉시 오답).
    /// 판단은 항상 `canEdit`을 쓴다 — `EffectivePermission.canEdit()` 단일 출처(Tell Don't Ask).
    /// `myRole`은 표시용이다(예: 읽기 전용 배지).
    public enum Role {
        VIEWER,
        EDITOR,
        OWNER
    }
}

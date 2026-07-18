package io.wedocs.doc.service;

/// PRD §4.2 유효 권한 해석 결과 — proto(common.Role)에 의존하지 않는 순수 도메인 타입.
/// proto 매핑은 gRPC 경계(DocServiceImpl)에서만 수행한다(layering-readability P2).
/// allowed는 role의 파생값 — role=NONE인데 allowed=true인 불법 상태 자체를 컴파일 타임에 배제한다
/// (design-patterns.md P4, validated construction).
public record EffectivePermission(EffectiveRole role) {

    public static final EffectivePermission DENIED = new EffectivePermission(EffectiveRole.NONE);

    public static EffectivePermission granted(EffectiveRole role) {
        return new EffectivePermission(role);
    }

    public boolean allowed() {
        return role != EffectiveRole.NONE;
    }

    /// PRD §4.3 레벨 표의 파생 판단 — 호출자가 role을 꺼내 비교하지 않는다(Tell Don't Ask).
    /// 읽기 가능 = 접근 허용과 동일 조건 — allowed()에 위임해 판정을 한 곳에 둔다.
    public boolean canRead() {
        return allowed();
    }

    public boolean canEdit() {
        return role == EffectiveRole.EDITOR || role == EffectiveRole.OWNER;
    }

    public enum EffectiveRole {
        NONE,
        VIEWER,
        EDITOR,
        OWNER
    }
}

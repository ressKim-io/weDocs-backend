package io.wedocs.doc.service;

/// PRD §4.2 유효 권한 해석 결과 — proto(common.Role)에 의존하지 않는 순수 도메인 타입.
/// proto 매핑은 gRPC 경계(DocServiceImpl)에서만 수행한다(layering-readability P2).
public record EffectivePermission(boolean allowed, EffectiveRole role) {

    public static final EffectivePermission DENIED = new EffectivePermission(false, EffectiveRole.NONE);

    public static EffectivePermission granted(EffectiveRole role) {
        return new EffectivePermission(true, role);
    }

    public enum EffectiveRole {
        NONE,
        VIEWER,
        EDITOR,
        OWNER
    }
}

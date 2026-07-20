package io.wedocs.gateway.grpc;

import io.wedocs.proto.common.Role;

/// `CheckPermission` 한 건의 결과. **거부(DENIED)와 백엔드 장애(BACKEND_ERROR)를 합치지 않는다** —
/// 둘 다 연결을 거절한다는 점에서는 같지만(fail-closed), 운영상 의미가 정반대이기 때문이다:
/// 전자는 정상 동작(권한 없는 사용자), 후자는 doc-service 단절로 **모든** 연결이 거절되는 장애 신호다
/// (ADR-0021 §알림 후보 = page). 한 값으로 뭉개면 대시보드에서 장애가 정상 거부에 묻힌다.
public record PermissionResult(Outcome outcome, Role role) {

    public enum Outcome {
        ALLOWED,
        DENIED,
        BACKEND_ERROR
    }

    private static final PermissionResult DENIED = new PermissionResult(Outcome.DENIED, Role.ROLE_UNSPECIFIED);
    private static final PermissionResult BACKEND_ERROR =
            new PermissionResult(Outcome.BACKEND_ERROR, Role.ROLE_UNSPECIFIED);

    public static PermissionResult allowed(Role role) {
        return new PermissionResult(Outcome.ALLOWED, role);
    }

    public static PermissionResult denied() {
        return DENIED;
    }

    public static PermissionResult backendError() {
        return BACKEND_ERROR;
    }
}

package io.wedocs.gateway.auth;

import io.wedocs.gateway.grpc.PermissionChecker;
import io.wedocs.gateway.grpc.PermissionResult;
import io.wedocs.gateway.ws.RoomHandshakeInterceptor;
import io.wedocs.gateway.ws.RoomId;
import io.wedocs.gateway.ws.SessionRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// WS 핸드셰이크 인가 게이트 (ADR-0014 · ADR-0021). 인증(`AuthHandshakeInterceptor`) 뒤에 실행돼
/// 검증된 user_id와 room으로 doc-service에 `CheckPermission`을 묻고, 권한이 없으면 **업그레이드 전 HTTP 403**으로
/// 거절한다 — 인증과 같은 이유로(세션·엔진 스트림 자원 미할당 + 상태코드가 L7 관측에 그대로 노출) 연결 후
/// WS close(4403)가 아니라 핸드셰이크 거절이다.
///
/// 통과 시 `SessionRole`을 세션 attribute로 넘긴다 — viewer의 쓰기 차단(`DocWebSocketHandler`)과
/// 엔진 방어층(2b)에 넘길 role 메타의 출처가 이 값이다.
///
/// 관측: 최종 성공(`ws_handshake_total{ok}`) 집계는 여기서 하지 않는다 — 인가 뒤에도 Origin 검사가 거절할 수
/// 있어 `AuthHandshakeInterceptor.afterHandshake`가 최종 상태코드를 보고 기록한다(H-1). 이 인터셉터가 403을
/// 세우면 그 afterHandshake가 `isRejected`로 걸러 ok를 세지 않는다.
@Component
public class AuthzHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthzHandshakeInterceptor.class);

    private final PermissionChecker permissionChecker;
    private final AuthMetrics metrics;

    public AuthzHandshakeInterceptor(PermissionChecker permissionChecker, AuthMetrics metrics) {
        this.permissionChecker = permissionChecker;
        this.metrics = metrics;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        Optional<String> docId = RoomHandshakeInterceptor.roomId(attributes).map(RoomId::value);
        Optional<String> userId = userId(attributes);
        if (docId.isEmpty() || userId.isEmpty()) {
            // 방어: 선행 인터셉터가 배선되지 않은 경우에만 발생. 인가 근거가 없으므로 통과시키지 않는다.
            return denied(response, HandshakeLog.NONE, HandshakeLog.NONE, "missing_identity", HandshakeLog.NONE);
        }

        String maskedUser = HandshakeLog.mask(userId.get());
        // doc-service 계약상 doc_id·user_id는 UUID다(아니면 INVALID_ARGUMENT). 형식 위반을 여기서 접으면
        // 무의미한 왕복이 사라지고, 결과도 달라지지 않는다 — doc-service는 존재하지 않는 문서를 NOT_FOUND가
        // 아니라 DENIED로 답해 존재 여부를 감추므로(IDOR 방지), 형식 오류의 정답도 똑같이 "거부"다.
        if (!isUuid(docId.get()) || !isUuid(userId.get())) {
            return denied(response, docId.get(), maskedUser, "invalid_doc_id", HandshakeLog.NONE);
        }

        long startNanos = System.nanoTime();
        PermissionResult result = permissionChecker.checkPermission(docId.get(), userId.get());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        metrics.checkPermission(elapsed);
        String elapsedMs = Long.toString(elapsed.toMillis());

        return switch (result.outcome()) {
            case ALLOWED -> grant(response, attributes, docId.get(), maskedUser, result, elapsedMs);
            case DENIED -> denied(response, docId.get(), maskedUser, "no_permission", elapsedMs);
            case BACKEND_ERROR -> backendError(response, docId.get(), maskedUser, elapsedMs);
        };
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // 성공 집계·로그는 AuthHandshakeInterceptor.afterHandshake가 최종 상태코드를 보고 단독으로 담당한다(H-1).
    }

    private boolean grant(
            ServerHttpResponse response,
            Map<String, Object> attributes,
            String docId,
            String maskedUser,
            PermissionResult result,
            String elapsedMs) {
        Optional<SessionRole> role = SessionRole.fromProto(result.role());
        if (role.isEmpty()) {
            // allowed=true인데 role을 해석할 수 없다 = 계약 위반이거나 게이트웨이가 모르는 신규 role.
            // 낙관 해석은 곧 권한 상승이므로 거절한다(fail-closed).
            return denied(response, docId, maskedUser, "unknown_role", elapsedMs);
        }
        attributes.put(SessionRole.ATTRIBUTE, role.get());
        // result= 는 ADR-0021이 열거한 **최종** 판정 값만 갖는다(ok|authn_fail|authz_denied|backend_error).
        // 인가 통과는 최종 판정이 아니라 중간 단계라 stage= 로 분리한다 — 계약 필드를 오버로드하면
        // `result=` 로 대시보드/알림을 거는 쪽이 열거값 밖의 값을 만나게 된다.
        log.debug("event=ws_handshake stage=authz_pass doc_id={} user={} role={} check_permission_ms={} trace_id={}",
                docId, maskedUser, role.get().wireValue(), elapsedMs, HandshakeLog.traceId());
        return true;
    }

    private boolean denied(
            ServerHttpResponse response, String docId, String maskedUser, String reason, String elapsedMs) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        log.warn("event=ws_handshake result=authz_denied reason={} doc_id={} user={} check_permission_ms={} trace_id={}",
                reason, docId, maskedUser, elapsedMs, HandshakeLog.traceId());
        metrics.handshake(AuthMetrics.RESULT_AUTHZ_DENIED);
        return false; // 업그레이드 전 거절 — 세션이 생성되지 않는다.
    }

    /// 인가 백엔드 불가. 거부와 같은 403을 주지만(클라이언트에게 권한 여부를 흘리지 않는다) **관측은 분리**한다 —
    /// 이 경로는 모든 사용자의 연결이 막히는 장애 신호다(ADR-0021 §알림 후보).
    private boolean backendError(ServerHttpResponse response, String docId, String maskedUser, String elapsedMs) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        log.error("event=ws_handshake result=backend_error reason=check_permission_unavailable doc_id={} user={} "
                + "check_permission_ms={} trace_id={}", docId, maskedUser, elapsedMs, HandshakeLog.traceId());
        metrics.handshake(AuthMetrics.RESULT_BACKEND_ERROR);
        metrics.authzBackendError();
        return false;
    }

    /// 인증 인터셉터가 앞서 넣어둔 검증된 user_id — raw Object 캐스트를 소유자 옆 한 곳에 가둔다.
    private static Optional<String> userId(Map<String, Object> attributes) {
        return Optional.ofNullable(attributes.get(AuthHandshakeInterceptor.USER_ID_ATTRIBUTE))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

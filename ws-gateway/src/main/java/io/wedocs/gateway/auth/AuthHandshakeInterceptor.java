package io.wedocs.gateway.auth;

import io.wedocs.gateway.ws.RoomHandshakeInterceptor;
import io.wedocs.gateway.ws.RoomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// WS 핸드셰이크 인증 게이트 (ADR-0014 · ADR-0021). `Sec-WebSocket-Protocol`에서 JWT를 꺼내 검증하고,
/// 실패(토큰 없음/무효/만료)는 **업그레이드 전 HTTP 401**로 거절한다 — 세션·엔진 스트림 자원이 할당되기
/// 전이라 무인증 플러드에 강하고, 상태코드가 access log·메시 텔레메트리에 그대로 노출돼 운영 관측이 쉽다.
/// (WS close 4401은 연결 후 종료 경로로 예약 — ADR-0021.) 성공 시 user_id를 세션 attribute로 넘긴다.
///
/// 관측(ADR-0021): 실패는 인터셉터가 즉시 기록(`authn_fail`·`jwt_verify`)하지만, **핸드셰이크 최종 성공
/// (`ws_handshake_total{ok}`)은 afterHandshake로 미룬다** — auth 통과 후에도 Origin 검사(setAllowedOrigins,
/// auth 인터셉터 뒤 프레임워크 단계)가 거절할 수 있어, before에서 낙관적으로 ok를 세면 최종 403 핸드셰이크가
/// ok로 오집계돼 앱 신호와 상태코드가 어긋난다(code-review H-1). afterHandshake는 최종 응답이 4xx가 아닐 때만 ok.
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    /// 검증된 user_id가 담기는 세션 attribute 키 — 연결시 인가(Phase 2a-2 CheckPermission)가 소비한다.
    public static final String USER_ID_ATTRIBUTE = "wedocs.userId";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    /// before→afterHandshake는 같은 요청·같은 스레드의 두 콜백인데 afterHandshake엔 attributes 맵이 없다.
    /// 성공 ok-로그에 필요한 필드를 요청 스코프로 넘기는 표준 수단 = ThreadLocal. afterHandshake에서 항상 제거하고,
    /// beforeHandshake가 true를 반환한 인터셉터엔 afterHandshake가 반드시 호출되므로(Spring 계약) 누수가 없다.
    private static final ThreadLocal<PendingHandshake> PENDING = new ThreadLocal<>();

    private final JwtVerifier verifier;
    private final AuthMetrics metrics;

    public AuthHandshakeInterceptor(JwtVerifier verifier, AuthMetrics metrics) {
        this.verifier = verifier;
        this.metrics = metrics;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String docId = docId(attributes);
        List<String> requested = AuthSubprotocol.flatten(
                request.getHeaders().get(WebSocketHttpHeaders.SEC_WEBSOCKET_PROTOCOL));
        Optional<String> token = AuthSubprotocol.extractToken(requested);
        if (token.isEmpty()) {
            metrics.handshake(AuthMetrics.RESULT_AUTHN_FAIL);
            return reject(response, docId, "no_token", HandshakeLog.NONE); // 검증 시도 자체가 없으므로 verify_ms 없음.
        }

        long startNanos = System.nanoTime();
        Optional<String> userId = verifier.verifySubject(token.get());
        String verifyMs = Long.toString((System.nanoTime() - startNanos) / 1_000_000L);
        if (userId.isEmpty()) {
            metrics.jwtVerify(AuthMetrics.RESULT_FAIL);
            metrics.handshake(AuthMetrics.RESULT_AUTHN_FAIL);
            // 검증은 실제 수행됐으므로 소요시간을 남긴다 — 느린 검증(JWKS 재조회) 실패를 운영상 구분(ADR-0021).
            return reject(response, docId, "invalid_token", verifyMs);
        }

        metrics.jwtVerify(AuthMetrics.RESULT_OK);
        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        // ok 집계·로그는 afterHandshake에서(최종 성공 시) — Origin 등 auth 이후 거절을 ok로 오집계 방지(H-1).
        PENDING.set(new PendingHandshake(docId, HandshakeLog.mask(userId.get()), verifyMs));
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        PendingHandshake pending = PENDING.get();
        PENDING.remove();
        if (pending == null) {
            return; // auth가 통과하지 못한 요청 — 이 인터셉터엔 afterHandshake가 오지 않지만 방어.
        }
        if (exception != null || isRejected(response)) {
            // auth는 통과했으나 이후 단계(Origin 등)가 핸드셰이크를 거절 → ok 아님(거절은 L7 상태코드로 관측).
            return;
        }
        metrics.handshake(AuthMetrics.RESULT_OK);
        log.info("event=ws_handshake result=ok doc_id={} user={} verify_ms={} trace_id={}",
                pending.docId(), pending.maskedUser(), pending.verifyMs(), HandshakeLog.traceId());
    }

    private boolean reject(ServerHttpResponse response, String docId, String reason, String verifyMs) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        // 토큰은 로깅하지 않는다(security.md) — 사유·doc_id·verify_ms·trace_id만. authn_fail은 access log의 401과 1:1 대응(ADR-0021).
        log.warn("event=ws_handshake result=authn_fail reason={} doc_id={} verify_ms={} trace_id={}",
                reason, docId, verifyMs, HandshakeLog.traceId());
        return false; // 업그레이드 전 거절 — 세션이 생성되지 않는다.
    }

    /// 핸드셰이크 최종 응답이 거절(4xx/5xx)인지 — auth 통과 후 Origin 등이 세운 상태코드를 읽어 ok 오집계를 막는다.
    private static boolean isRejected(ServerHttpResponse response) {
        return response instanceof ServletServerHttpResponse servletResponse
                && servletResponse.getServletResponse().getStatus() >= HttpStatus.BAD_REQUEST.value();
    }

    /// room 인터셉터가 auth보다 먼저 실행돼(WebSocketConfig 순서) 검증된 RoomId를 넣어둔다 — 그 값을 재사용한다.
    /// attribute의 raw Object 캐스트는 소유자(RoomHandshakeInterceptor.roomId)에 캡슐화 — 여기선 RoomId만 다룬다.
    private static String docId(Map<String, Object> attributes) {
        return RoomHandshakeInterceptor.roomId(attributes).map(RoomId::value).orElse(HandshakeLog.NONE);
    }

    /// afterHandshake의 성공 로그에 필요한 요청 스코프 상태(before→after 전달용).
    private record PendingHandshake(String docId, String maskedUser, String verifyMs) {
    }
}

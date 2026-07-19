package io.wedocs.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.ws.RoomHandshakeInterceptor;
import io.wedocs.gateway.ws.RoomId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// 핸드셰이크 인증 인터셉터 — 판정 경로(유효/무토큰/무효토큰)와 관측을 Mock 서블릿 요청으로 격리 검증한다.
/// 특히 성공 ok 집계는 afterHandshake의 최종 상태에 달려 있음을 직접 검증한다(auth 통과 후 Origin 등이
/// 거절하면 ok로 세지 않는다 — H-1). 통합테스트는 실제 업그레이드 와이어를 담당.
class AuthHandshakeInterceptorTest {

    private static final String ISSUER = "wedocs-doc-service";
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";
    private static final String ROOM = "demo";

    private static RSAKey signingKey;

    private SimpleMeterRegistry registry;
    private AuthHandshakeInterceptor interceptor;

    @BeforeAll
    static void generateKey() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
    }

    @BeforeEach
    void setUp() {
        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        JwtVerifier verifier = JwtVerifier.fromKeySource(keySource, ISSUER, Duration.ofSeconds(60));
        registry = new SimpleMeterRegistry();
        interceptor = new AuthHandshakeInterceptor(verifier, new AuthMetrics(registry));
    }

    @Test
    @DisplayName("유효 토큰 + 최종 성공 → 통과 + user_id attribute + ok는 afterHandshake에서 집계")
    void validToken_recordsOkOnSuccessfulHandshake() throws Exception {
        // Given/When: [SENTINEL, 유효 JWT]로 beforeHandshake
        Handshake handshake = before(List.of(AuthSubprotocol.SENTINEL, sign(validClaims())));

        // Then: 업그레이드 허용·subject 세션 attribute·검증 ok. 단 ws_handshake ok는 아직 안 셈(afterHandshake 몫).
        assertThat(handshake.accepted()).isTrue();
        assertThat(handshake.attributes()).containsEntry(AuthHandshakeInterceptor.USER_ID_ATTRIBUTE, SUBJECT);
        assertThat(count("jwt.verify", AuthMetrics.RESULT_OK)).isEqualTo(1.0);
        assertThat(count("ws.handshake", AuthMetrics.RESULT_OK)).isZero();

        // When: 최종 성공(101)으로 afterHandshake
        after(handshake, HttpStatus.SWITCHING_PROTOCOLS.value());

        // Then: 그제서야 ok 집계
        assertThat(count("ws.handshake", AuthMetrics.RESULT_OK)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("유효 토큰이라도 최종 응답이 거절(403)이면 ok로 세지 않는다(H-1)")
    void validToken_butDownstreamRejected_doesNotRecordOk() throws Exception {
        // Given: 인증은 통과했으나(검증 ok)
        Handshake handshake = before(List.of(AuthSubprotocol.SENTINEL, sign(validClaims())));
        assertThat(handshake.accepted()).isTrue();

        // When: 이후 단계(Origin 등)가 403으로 거절
        after(handshake, HttpStatus.FORBIDDEN.value());

        // Then: 검증 성공은 셌지만 핸드셰이크 ok는 아님 — 앱 신호와 상태코드 정합(ADR-0021).
        assertThat(count("jwt.verify", AuthMetrics.RESULT_OK)).isEqualTo(1.0);
        assertThat(count("ws.handshake", AuthMetrics.RESULT_OK)).isZero();
    }

    @Test
    @DisplayName("토큰 없음 → 401 거절 + authn_fail 메트릭(검증 시도는 세지 않음)")
    void noToken_rejectedWithoutVerifyMetric() {
        // Given: 서브프로토콜 헤더 없음
        Handshake handshake = before(null);

        // Then: 업그레이드 전 401, user_id 미설정, jwt_verify 미발화(검증 시도 아님)
        assertThat(handshake.accepted()).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(handshake.attributes()).doesNotContainKey(AuthHandshakeInterceptor.USER_ID_ATTRIBUTE);
        assertThat(count("ws.handshake", AuthMetrics.RESULT_AUTHN_FAIL)).isEqualTo(1.0);
        assertThat(count("jwt.verify", AuthMetrics.RESULT_OK)).isZero();
        assertThat(count("jwt.verify", AuthMetrics.RESULT_FAIL)).isZero();
    }

    @Test
    @DisplayName("무효 토큰 → 401 거절 + jwt_verify fail + authn_fail 메트릭")
    void invalidToken_rejectedWithVerifyFail() {
        // Given: [SENTINEL, JWT 아님]
        Handshake handshake = before(List.of(AuthSubprotocol.SENTINEL, "not-a-jwt"));

        // Then: 401, 검증 실패·핸드셰이크 실패 각 1회
        assertThat(handshake.accepted()).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(count("jwt.verify", AuthMetrics.RESULT_FAIL)).isEqualTo(1.0);
        assertThat(count("ws.handshake", AuthMetrics.RESULT_AUTHN_FAIL)).isEqualTo(1.0);
    }

    /// room 인터셉터가 먼저 넣어둔 검증된 RoomId를 모사해 beforeHandshake를 1회 실행한다.
    private Handshake before(List<String> subprotocols) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/doc/" + ROOM);
        if (subprotocols != null) {
            servletRequest.addHeader(WebSocketHttpHeaders.SEC_WEBSOCKET_PROTOCOL, String.join(", ", subprotocols));
        }
        ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RoomHandshakeInterceptor.ROOM_ATTRIBUTE, new RoomId(ROOM));

        boolean accepted = interceptor.beforeHandshake(request, response, null, attributes);
        return new Handshake(request, response, servletResponse, accepted, attributes);
    }

    /// beforeHandshake가 통과한 뒤 핸드셰이크가 최종 상태(status)로 끝났을 때의 afterHandshake를 모사한다.
    private void after(Handshake handshake, int finalStatus) {
        handshake.servletResponse().setStatus(finalStatus);
        interceptor.afterHandshake(handshake.request(), handshake.response(), null, null);
    }

    private double count(String meter, String result) {
        var counter = registry.find(meter).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static JWTClaimsSet validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(SUBJECT).issuer(ISSUER)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private record Handshake(ServerHttpRequest request, ServerHttpResponse response,
            MockHttpServletResponse servletResponse, boolean accepted, Map<String, Object> attributes) {
        int status() {
            return servletResponse.getStatus();
        }
    }
}

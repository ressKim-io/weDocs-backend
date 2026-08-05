package io.wedocs.gateway.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.common.logging.CapturedLogs;
import io.wedocs.gateway.common.logging.GatewayErrorType;
import io.wedocs.gateway.common.logging.LogFields;
import io.wedocs.gateway.grpc.PermissionChecker;
import io.wedocs.gateway.grpc.PermissionResult;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import io.wedocs.proto.common.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// **Property 9: 핸드셰이크 관측 계약 유지**
///
/// **Validates: Requirements 3.8, 6.1, 6.3, 6.7**
///
/// 시나리오 생성기(토큰 유무 × 검증 결과 × 권한 판정 × 역할)로 5개 판정 경로 전부를 덮고,
/// 개명된 속성 집합·열거값·레벨을 확인한다.
///
/// - `ok`: 인증·인가 모두 통과 → INFO, event.name=ws_handshake, wedocs.result=ok, doc_id, user.hash
/// - `authn_fail/no_token`: 토큰 없음 → WARN, error.type=no_token, doc_id
/// - `authn_fail/invalid_token`: 검증 실패 → WARN, error.type=invalid_token, doc_id, verify duration 존재
/// - `authz_pass`: 인가 통과 중간 단계 → DEBUG, wedocs.handshake.stage=authz_pass, role, doc_id, user.hash
/// - `authz_denied`: 인가 거부 → WARN, error.type 존재, doc_id, user.hash
/// - `backend_error`: 백엔드 장애 → ERROR, error.type=check_permission_unavailable, doc_id, user.hash
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 9: 핸드셰이크 관측 계약 유지")
class HandshakeObservationPropertyTest {

    /// 5개 핸드셰이크 판정 경로를 전수 표현한다.
    enum Scenario {
        /// 인증 실패: 토큰 없음
        AUTHN_NO_TOKEN,
        /// 인증 실패: 토큰 무효
        AUTHN_INVALID_TOKEN,
        /// 인가 통과 (중간 단계) + 최종 ok
        AUTHZ_PASS_AND_OK,
        /// 인가 거부
        AUTHZ_DENIED,
        /// 인가 백엔드 장애
        BACKEND_ERROR
    }

    record HandshakeInput(Scenario scenario, String docId, String userId, Role role) {
    }

    @Property(tries = 100)
    void handshakeScenario_producesCorrectAttributes(@ForAll("handshakeInputs") HandshakeInput input) {
        switch (input.scenario()) {
            case AUTHN_NO_TOKEN -> verifyAuthnNoToken(input);
            case AUTHN_INVALID_TOKEN -> verifyAuthnInvalidToken(input);
            case AUTHZ_PASS_AND_OK -> verifyAuthzPassAndOk(input);
            case AUTHZ_DENIED -> verifyAuthzDenied(input);
            case BACKEND_ERROR -> verifyBackendError(input);
        }
    }

    private void verifyAuthnNoToken(HandshakeInput input) {
        // AuthHandshakeInterceptor에서 no_token 경로
        var setup = authnSetup();
        try (var logs = CapturedLogs.of(AuthHandshakeInterceptor.class)) {
            // 토큰 없이 beforeHandshake
            boolean proceed = setup.authn().beforeHandshake(
                    request(null), setup.response(), null, attributes(input.docId()));

            assertThat(proceed).isFalse();
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // WARN 레벨, event.name=ws_handshake, wedocs.result=authn_fail
            assertThat(event.level()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getString(LogFields.EVENT_NAME)).isEqualTo("ws_handshake");
            assertThat(event.getString(LogFields.RESULT)).isEqualTo(AuthMetrics.RESULT_AUTHN_FAIL);
            assertThat(event.getString(LogFields.ERROR_TYPE)).isEqualTo(GatewayErrorType.NO_TOKEN.value());
            assertThat(event.getString(LogFields.DOC_ID)).isEqualTo(input.docId());
            // verify duration 속성 없음 (검증 수행 안 됨)
            assertThat(event.hasKey(LogFields.HANDSHAKE_VERIFY_MS)).isFalse();
        }
    }

    private void verifyAuthnInvalidToken(HandshakeInput input) {
        var setup = authnSetup();
        try (var logs = CapturedLogs.of(AuthHandshakeInterceptor.class)) {
            // 무효 토큰으로 beforeHandshake
            boolean proceed = setup.authn().beforeHandshake(
                    request("not-a-jwt"), setup.response(), null, attributes(input.docId()));

            assertThat(proceed).isFalse();
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // WARN 레벨, error.type=invalid_token, verify duration 존재
            assertThat(event.level()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getString(LogFields.EVENT_NAME)).isEqualTo("ws_handshake");
            assertThat(event.getString(LogFields.RESULT)).isEqualTo(AuthMetrics.RESULT_AUTHN_FAIL);
            assertThat(event.getString(LogFields.ERROR_TYPE)).isEqualTo(GatewayErrorType.INVALID_TOKEN.value());
            assertThat(event.getString(LogFields.DOC_ID)).isEqualTo(input.docId());
            // verify duration 존재 (검증은 수행됨)
            assertThat(event.hasKey(LogFields.HANDSHAKE_VERIFY_MS)).isTrue();
            assertThat(event.getLong(LogFields.HANDSHAKE_VERIFY_MS)).isGreaterThanOrEqualTo(0L);
        }
    }

    private void verifyAuthzPassAndOk(HandshakeInput input) {
        var setup = authzSetup(PermissionResult.allowed(input.role()));

        // authz_pass 중간 단계 로그 — DEBUG level이므로 로거 레벨을 ALL로 설정
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AuthzHandshakeInterceptor.class);
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.ALL);

        try (var logs = CapturedLogs.of(AuthzHandshakeInterceptor.class)) {
            Map<String, Object> attrs = attributesWithUser(input.docId(), input.userId());
            boolean proceed = setup.authz().beforeHandshake(
                    new ServletServerHttpRequest(new MockHttpServletRequest()),
                    setup.response(), null, attrs);

            assertThat(proceed).isTrue();
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // DEBUG 레벨, wedocs.handshake.stage=authz_pass
            assertThat(event.level()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
            assertThat(event.getString(LogFields.EVENT_NAME)).isEqualTo("ws_handshake");
            assertThat(event.getString(LogFields.HANDSHAKE_STAGE)).isEqualTo("authz_pass");
            assertThat(event.getString(LogFields.DOC_ID)).isEqualTo(input.docId());
            assertThat(event.getString(LogFields.USER_HASH)).isNotNull();
            assertThat(event.getString(LogFields.DOC_ROLE)).isNotNull();
            // wedocs.result 부재 (중간 단계라 최종 결과가 아님)
            assertThat(event.hasKey(LogFields.RESULT)).isFalse();
            // check_permission duration 존재
            assertThat(event.hasKey(LogFields.HANDSHAKE_CHECK_PERMISSION_MS)).isTrue();
        } finally {
            logger.setLevel(originalLevel);
        }

        // HANDSHAKE_OK 최종 로그 — AuthHandshakeInterceptor.afterHandshake에서 발생
        // (여기선 AuthHandshakeInterceptor의 ok 경로를 직접 검증할 수 없으나,
        //  LogEvents + GatewayLogEvent.HANDSHAKE_OK의 속성 세트는 Property 2가 커버)
    }

    private void verifyAuthzDenied(HandshakeInput input) {
        var setup = authzSetup(PermissionResult.denied());

        try (var logs = CapturedLogs.of(AuthzHandshakeInterceptor.class)) {
            Map<String, Object> attrs = attributesWithUser(input.docId(), input.userId());
            boolean proceed = setup.authz().beforeHandshake(
                    new ServletServerHttpRequest(new MockHttpServletRequest()),
                    setup.response(), null, attrs);

            assertThat(proceed).isFalse();
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // WARN 레벨, wedocs.result=authz_denied, error.type 존재
            assertThat(event.level()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getString(LogFields.EVENT_NAME)).isEqualTo("ws_handshake");
            assertThat(event.getString(LogFields.RESULT)).isEqualTo(AuthMetrics.RESULT_AUTHZ_DENIED);
            assertThat(event.getString(LogFields.ERROR_TYPE)).isEqualTo(GatewayErrorType.NO_PERMISSION.value());
            assertThat(event.getString(LogFields.DOC_ID)).isEqualTo(input.docId());
            assertThat(event.getString(LogFields.USER_HASH)).isNotNull();
        }
    }

    private void verifyBackendError(HandshakeInput input) {
        var setup = authzSetup(PermissionResult.backendError());

        try (var logs = CapturedLogs.of(AuthzHandshakeInterceptor.class)) {
            Map<String, Object> attrs = attributesWithUser(input.docId(), input.userId());
            boolean proceed = setup.authz().beforeHandshake(
                    new ServletServerHttpRequest(new MockHttpServletRequest()),
                    setup.response(), null, attrs);

            assertThat(proceed).isFalse();
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // ERROR 레벨, wedocs.result=backend_error, error.type=check_permission_unavailable
            assertThat(event.level()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(event.getString(LogFields.EVENT_NAME)).isEqualTo("ws_handshake");
            assertThat(event.getString(LogFields.RESULT)).isEqualTo(AuthMetrics.RESULT_BACKEND_ERROR);
            assertThat(event.getString(LogFields.ERROR_TYPE))
                    .isEqualTo(GatewayErrorType.CHECK_PERMISSION_UNAVAILABLE.value());
            assertThat(event.getString(LogFields.DOC_ID)).isEqualTo(input.docId());
            assertThat(event.getString(LogFields.USER_HASH)).isNotNull();
        }
    }

    // ── 어비트레리 ──

    @Provide
    Arbitrary<HandshakeInput> handshakeInputs() {
        Arbitrary<Scenario> scenarioArb = Arbitraries.of(Scenario.values());
        Arbitrary<String> docIdArb = Arbitraries.create(() -> UUID.randomUUID().toString());
        Arbitrary<String> userIdArb = Arbitraries.create(() -> UUID.randomUUID().toString());
        Arbitrary<Role> roleArb = Arbitraries.of(Role.ROLE_VIEWER, Role.ROLE_EDITOR, Role.ROLE_OWNER);

        return Combinators.combine(scenarioArb, docIdArb, userIdArb, roleArb)
                .as(HandshakeInput::new);
    }

    // ── 유틸 ──

    /// 인증 단계 로그 검증에 필요한 JwtVerifier가 항상 empty를 돌려주는 최소 세팅.
    /// (no_token은 verifier를 호출하지 않고, invalid_token은 파싱 실패 → empty.)
    private AuthnSetup authnSetup() {
        // JwtVerifier가 항상 empty를 반환하도록 간이 구현 — invalid_token 경로용.
        JwtVerifier alwaysReject = new JwtVerifier(null) {
            @Override
            public java.util.Optional<String> verifySubject(String token) {
                return java.util.Optional.empty();
            }
        };
        var registry = new SimpleMeterRegistry();
        var metrics = new AuthMetrics(registry);
        var interceptor = new AuthHandshakeInterceptor(alwaysReject, metrics);
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);
        return new AuthnSetup(interceptor, response);
    }

    private AuthzSetup authzSetup(PermissionResult result) {
        PermissionChecker stub = (docId, userId) -> result;
        var registry = new SimpleMeterRegistry();
        var metrics = new AuthMetrics(registry);
        var interceptor = new AuthzHandshakeInterceptor(stub, metrics);
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);
        return new AuthzSetup(interceptor, response);
    }

    private ServerHttpRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/doc/test");
        if (token != null) {
            req.addHeader("Sec-WebSocket-Protocol",
                    AuthSubprotocol.SENTINEL + ", " + token);
        }
        return new ServletServerHttpRequest(req);
    }

    private Map<String, Object> attributes(String docId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HandshakeAttributes.ROOM_ATTRIBUTE, new RoomId(docId));
        return attrs;
    }

    private Map<String, Object> attributesWithUser(String docId, String userId) {
        Map<String, Object> attrs = attributes(docId);
        attrs.put(AuthHandshakeInterceptor.USER_ID_ATTRIBUTE, userId);
        return attrs;
    }

    private record AuthnSetup(AuthHandshakeInterceptor authn, ServerHttpResponse response) {
    }

    private record AuthzSetup(AuthzHandshakeInterceptor authz, ServerHttpResponse response) {
    }
}

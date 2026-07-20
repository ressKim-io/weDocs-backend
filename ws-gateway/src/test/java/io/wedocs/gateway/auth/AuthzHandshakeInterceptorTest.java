package io.wedocs.gateway.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.grpc.PermissionChecker;
import io.wedocs.gateway.grpc.PermissionResult;
import io.wedocs.gateway.ws.RoomHandshakeInterceptor;
import io.wedocs.gateway.ws.RoomId;
import io.wedocs.gateway.ws.SessionRole;
import io.wedocs.proto.common.Role;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// 핸드셰이크 인가 게이트 검증 (ADR-0014 · ADR-0021). 관측은 실제 MeterRegistry로 계측값을 읽어 확인한다
/// (계약이 메트릭 이름·태그이므로 mock 호출 검증으로는 계약을 못 지킨다).
class AuthzHandshakeInterceptorTest {

    private static final String DOC_ID = "11111111-1111-4111-8111-111111111111";
    private static final String USER_ID = "44444444-4444-4444-8444-444444444444";

    private SimpleMeterRegistry registry;
    private AuthMetrics metrics;
    private StubDocServiceClient docService;
    private AuthzHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AuthMetrics(registry);
        docService = new StubDocServiceClient();
        interceptor = new AuthzHandshakeInterceptor(docService, metrics);
    }

    @Test
    @DisplayName("editor 권한이면 핸드셰이크를 통과시키고 세션 role=EDITOR를 넘긴다")
    void editor_allowsHandshakeAndPropagatesRole() {
        // Given: doc-service가 editor를 돌려준다
        docService.result = PermissionResult.allowed(Role.ROLE_EDITOR);
        Handshake handshake = handshake(DOC_ID, USER_ID);

        // When
        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        // Then: 통과 + role attribute. ok 집계는 여기서 하지 않는다(H-1 — afterHandshake 소관).
        assertThat(proceed).isTrue();
        assertThat(SessionRole.from(handshake.attributes())).contains(SessionRole.EDITOR);
        assertThat(count(AuthMetrics.RESULT_AUTHZ_DENIED)).isZero();
    }

    @Test
    @DisplayName("owner 권한은 editor 세션으로 접힌다(게이트웨이는 둘을 다르게 취급하지 않는다)")
    void owner_collapsesToEditor() {
        docService.result = PermissionResult.allowed(Role.ROLE_OWNER);
        Handshake handshake = handshake(DOC_ID, USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isTrue();
        assertThat(SessionRole.from(handshake.attributes())).contains(SessionRole.EDITOR);
    }

    @Test
    @DisplayName("viewer 권한이면 통과시키되 세션 role=VIEWER로 표시한다(쓰기 차단은 핸들러 몫)")
    void viewer_allowsHandshakeAsReadOnly() {
        docService.result = PermissionResult.allowed(Role.ROLE_VIEWER);
        Handshake handshake = handshake(DOC_ID, USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isTrue();
        assertThat(SessionRole.from(handshake.attributes())).contains(SessionRole.VIEWER);
    }

    @Test
    @DisplayName("권한 없음(allowed=false)은 업그레이드 전 403으로 거절된다")
    void denied_rejectsWith403() {
        docService.result = PermissionResult.denied();
        Handshake handshake = handshake(DOC_ID, USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(SessionRole.from(handshake.attributes())).isEmpty();
        assertThat(count(AuthMetrics.RESULT_AUTHZ_DENIED)).isEqualTo(1);
    }

    @Test
    @DisplayName("allowed=true인데 role이 UNSPECIFIED면 거절한다(낙관 해석 = 조용한 권한 상승)")
    void allowedWithUnknownRole_rejects() {
        docService.result = PermissionResult.allowed(Role.ROLE_UNSPECIFIED);
        Handshake handshake = handshake(DOC_ID, USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(count(AuthMetrics.RESULT_AUTHZ_DENIED)).isEqualTo(1);
    }

    @Test
    @DisplayName("인가 백엔드 장애는 403으로 거절하고 정상 거부와 구분해 계측한다(fail-closed + 알림 신호)")
    void backendError_rejectsAndCountsSeparately() {
        docService.result = PermissionResult.backendError();
        Handshake handshake = handshake(DOC_ID, USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(count(AuthMetrics.RESULT_BACKEND_ERROR)).isEqualTo(1);
        assertThat(count(AuthMetrics.RESULT_AUTHZ_DENIED)).isZero(); // 장애가 정상 거부에 묻히지 않는다
        assertThat(registry.find(AuthMetrics.AUTHZ_BACKEND_ERROR).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("UUID가 아닌 doc_id는 gRPC 왕복 없이 403으로 접는다(doc-service 계약 위반 선차단)")
    void nonUuidDocId_rejectsWithoutRemoteCall() {
        Handshake handshake = handshake("not-a-uuid", USER_ID);

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(docService.calls.get()).isZero();
        assertThat(count(AuthMetrics.RESULT_AUTHZ_DENIED)).isEqualTo(1);
    }

    @Test
    @DisplayName("UUID가 아닌 user_id(토큰 subject)도 같은 이유로 403이다")
    void nonUuidUserId_rejectsWithoutRemoteCall() {
        Handshake handshake = handshake(DOC_ID, "it-user");

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(docService.calls.get()).isZero();
    }

    @Test
    @DisplayName("선행 인터셉터가 없어 room·user_id가 비면 통과시키지 않는다(fail-closed)")
    void missingIdentity_rejects() {
        Handshake handshake = new Handshake(new HashMap<>()); // room·user 미주입

        boolean proceed = interceptor.beforeHandshake(
                handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(proceed).isFalse();
        assertThat(handshake.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(docService.calls.get()).isZero();
    }

    @Test
    @DisplayName("CheckPermission 소요시간은 성공·거부·장애 모두 기록된다(deadline 조정 근거)")
    void checkPermissionDuration_isRecordedForEveryOutcome() {
        docService.result = PermissionResult.denied();
        Handshake handshake = handshake(DOC_ID, USER_ID);

        interceptor.beforeHandshake(handshake.request(), handshake.response(), null, handshake.attributes());

        assertThat(registry.find(AuthMetrics.CHECK_PERMISSION).timer().count()).isEqualTo(1);
    }

    private double count(String result) {
        var counter = registry.find(AuthMetrics.HANDSHAKE).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static Handshake handshake(String docId, String userId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RoomHandshakeInterceptor.ROOM_ATTRIBUTE, new RoomId(docId));
        attributes.put(AuthHandshakeInterceptor.USER_ID_ATTRIBUTE, userId);
        return new Handshake(attributes);
    }

    /// 한 번의 핸드셰이크 요청/응답 쌍 — 상태코드를 실제 서블릿 응답에서 읽어 "403을 세웠는가"를 검증한다.
    private record Handshake(Map<String, Object> attributes, MockHttpServletResponse servletResponse) {

        Handshake(Map<String, Object> attributes) {
            this(attributes, new MockHttpServletResponse());
        }

        ServerHttpRequest request() {
            return new ServletServerHttpRequest(new MockHttpServletRequest());
        }

        ServerHttpResponse response() {
            return new ServletServerHttpResponse(servletResponse);
        }

        int status() {
            return servletResponse.getStatus();
        }
    }

    /// doc-service 대역(단위). 호출 횟수를 세어 "형식 위반은 왕복 없이 접힌다"를 검증한다.
    private static final class StubDocServiceClient implements PermissionChecker {

        private final AtomicInteger calls = new AtomicInteger();
        private PermissionResult result = PermissionResult.allowed(Role.ROLE_EDITOR);

        @Override
        public PermissionResult checkPermission(String docId, String userId) {
            calls.incrementAndGet();
            return result;
        }
    }
}

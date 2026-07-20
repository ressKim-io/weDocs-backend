package io.wedocs.gateway.ws;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.wedocs.gateway.auth.AuthSubprotocol;
import io.wedocs.gateway.auth.JwtVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/// WS 통합 테스트 공통 스캐폴딩 — 실 Tomcat + 실 gRPC 대역(엔진·doc-service)에 붙는다.
///
/// 하위 클래스가 **같은 애노테이션·같은 프로퍼티**를 상속하므로 Spring 컨텍스트 캐시 키가 일치해
/// 컨텍스트가 한 번만 뜬다. 클래스마다 자체 `TestAuthConfig`를 두면 캐시 키가 갈려 부팅이 중복된다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractWsIntegrationTest.TestAuthConfig.class)
abstract class AbstractWsIntegrationTest {

    protected static final long TIMEOUT_MS = 5_000;
    /// 프레임이 "오지 않음"을 증명할 때의 대기 — 정상 경로(로컬 루프백)보다 충분히 길되 테스트를 늘리지 않는 값.
    protected static final long ABSENCE_TIMEOUT_MS = 500;

    protected static final String ISSUER = "wedocs-doc-service";
    /// room(=doc_id)·subject는 UUID여야 한다 — doc-service CheckPermission이 둘 다 UUID로 파싱하고,
    /// 게이트웨이는 형식 위반을 gRPC 왕복 없이 403으로 접는다(2a-2 D1).
    protected static final String USER_ID = "44444444-4444-4444-8444-444444444444";

    @LocalServerPort
    protected int port;

    @Autowired
    protected MeterRegistry meterRegistry;

    private final List<WebSocketSession> openedSessions = new ArrayList<>();

    @DynamicPropertySource
    static void backendTargets(DynamicPropertyRegistry registry) {
        registry.add("wedocs.engine.target", () -> "localhost:" + WsBackends.ENGINE_PORT);
        registry.add("wedocs.doc-service.target", () -> "localhost:" + WsBackends.DOC_SERVICE_PORT);
        // deadline 초과 시나리오를 짧게 재현하기 위해 줄인다(모든 통합 테스트에 동일 적용 — 컨텍스트 공유 조건).
        registry.add("wedocs.doc-service.check-permission-timeout", () -> "300ms");
    }

    @BeforeEach
    void resetBackends() {
        WsBackends.ENGINE.reset();
        WsBackends.DOC_SERVICE.reset(); // 기본 = editor 허용
    }

    @AfterEach
    void closeSessions() {
        // 어서션 실패로 테스트 본문의 close가 건너뛰어져도 세션이 누수되지 않도록 무조건 정리(테스트 격리).
        openedSessions.forEach(session -> {
            try {
                session.close();
            } catch (Exception ignored) {
                // 이미 닫힌 세션이면 무시
            }
        });
        openedSessions.clear();
    }

    protected FakeCrdtEngine engine() {
        return WsBackends.ENGINE;
    }

    protected FakeDocService docService() {
        return WsBackends.DOC_SERVICE;
    }

    /// 유효 JWT를 `Sec-WebSocket-Protocol`([SENTINEL, jwt])로 실어 인증을 통과시킨다.
    protected WebSocketSession connect(BinaryWebSocketHandler handler, String room) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of(AuthSubprotocol.SENTINEL, validToken()));
        String url = "ws://localhost:" + port + "/ws/doc/" + room;
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, headers, URI.create(url))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        openedSessions.add(session); // @AfterEach에서 무조건 정리되도록 추적
        return session;
    }

    /// 테스트 JWKS 키로 서명한 유효 토큰(sub/iss/exp) — 발급측 doc-service 토큰과 동일 구조.
    protected String validToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(USER_ID).issuer(ISSUER)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestAuthConfig.key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(TestAuthConfig.key));
        return jwt.serialize();
    }

    protected double handshakeCount(String result) {
        var counter = meterRegistry.find("ws.handshake").tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /// WS 클라이언트가 받은 바이너리 메시지를 모은다.
    protected static final class CollectingHandler extends BinaryWebSocketHandler {

        final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            received.add(bytes);
        }
    }

    /// 인증 검증기를 in-memory 테스트 키로 대체(원격 doc-service JWKS 불필요). @Primary라 실 배선(AuthConfig)
    /// 대신 주입된다 — 이 키로 서명한 토큰만 통과. 실 verifier 빈은 지연 fetch라 무해하게 공존한다.
    @TestConfiguration
    static class TestAuthConfig {

        static RSAKey key;

        @Bean
        @Primary
        JwtVerifier testJwtVerifier() throws Exception {
            key = new RSAKeyGenerator(2048).keyID("it-kid").generate();
            JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
            return JwtVerifier.fromKeySource(keySource, ISSUER, Duration.ofSeconds(60));
        }
    }
}

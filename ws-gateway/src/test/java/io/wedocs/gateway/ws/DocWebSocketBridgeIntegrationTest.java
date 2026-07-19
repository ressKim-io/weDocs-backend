package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
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
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.wedocs.gateway.auth.AuthSubprotocol;
import io.wedocs.gateway.auth.JwtVerifier;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.CrdtEngineGrpc;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// raw WS 클라이언트 ↔ gateway ↔ fake in-process gRPC 엔진 종단 검증.
/// 검증: (1) doc-id 메타데이터 전파 + 인바운드 SyncStep1 forward, (2) ServerFrame{update} → WS Update(2) fan-out(2클라).
/// 실제 Rust 엔진 수렴은 로컬 스모크 + Phase 3 E2E가 담당(여기선 와이어↔gRPC 브리지 배선만 결정적으로 검증).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DocWebSocketBridgeIntegrationTest.TestAuthConfig.class)
class DocWebSocketBridgeIntegrationTest {

    private static final long TIMEOUT_MS = 5_000;

    private static final FakeCrdtEngine ENGINE = new FakeCrdtEngine();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    private final List<WebSocketSession> openedSessions = new ArrayList<>();

    @DynamicPropertySource
    static void engineTarget(DynamicPropertyRegistry registry) {
        int grpcPort = ENGINE.startOnRandomPort();
        registry.add("wedocs.engine.target", () -> "localhost:" + grpcPort);
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.stop();
    }

    @BeforeEach
    void resetEngine() {
        ENGINE.reset();
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

    @Test
    @DisplayName("WS 접속 시 doc-id 메타데이터가 엔진에 전파되고 SyncStep1이 ClientFrame{state_vector}로 forward된다")
    void inbound_syncStep1_propagatesMetadataAndForwardsFrame() throws Exception {
        // Given: /ws/doc/demo 접속
        CollectingHandler client = new CollectingHandler();
        WebSocketSession session = connect(client, "demo");

        // When: 브라우저가 SyncStep1(SV={1,2,3}) 송신
        session.sendMessage(new BinaryMessage(new byte[]{0x00, 0x00, 0x03, 1, 2, 3}));

        // Then: 엔진이 메타데이터 doc-id=demo 를 보았고, ClientFrame{doc_id=demo, state_vector={1,2,3}} 수신
        assertThat(ENGINE.observedDocIds.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo("demo");
        ClientFrame frame = ENGINE.receivedFrames.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame.getDocId()).isEqualTo("demo");
        assertThat(frame.getStateVector().toByteArray()).containsExactly(1, 2, 3);
        assertThat(frame.getUpdate().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("한 클라의 update가 엔진 broadcast로 두 클라 모두에게 WS Update(2)로 fan-out된다")
    void update_fansOutToAllClientsAsUpdate2() throws Exception {
        // Given: 같은 room에 두 클라 접속 + 두 gRPC 스트림 등록 완료까지 대기
        CollectingHandler clientA = new CollectingHandler();
        CollectingHandler clientB = new CollectingHandler();
        WebSocketSession sessionA = connect(clientA, "room");
        WebSocketSession sessionB = connect(clientB, "room");
        ENGINE.awaitObservers(2, TIMEOUT_MS);

        // When: A가 Update(payload={0x55,0x66}) 송신 → 엔진 broadcast
        sessionA.sendMessage(new BinaryMessage(new byte[]{0x00, 0x02, 0x02, 0x55, 0x66}));

        // Then: 두 클라 모두 WS Update(2) 프레임 수신 (messageSync=0, Update=2, len=2, payload)
        byte[] expected = {0x00, 0x02, 0x02, 0x55, 0x66};
        assertThat(clientA.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(expected);
        assertThat(clientB.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(expected);
    }

    @Test
    @DisplayName("WS 클라가 종료되면 엔진 요청 스트림이 onCompleted로 정리된다 (스트림 누수 방지)")
    void clientDisconnect_completesEngineStream() throws Exception {
        // Given: 접속 + gRPC 스트림 등록 완료
        CollectingHandler client = new CollectingHandler();
        WebSocketSession session = connect(client, "bye");
        ENGINE.awaitObservers(1, TIMEOUT_MS);

        // When: 클라가 연결을 닫음
        session.close();

        // Then: 게이트웨이가 엔진 요청 스트림을 완료시켜 누수가 없다
        assertThat(ENGINE.completedStreams.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
    }

    @Test
    @DisplayName("형식 위반 room(불허 문자)은 핸드셰이크 단계에서 거절된다(업그레이드 전, 엔진 미관측)")
    void invalidRoom_rejectedAtHandshake() throws Exception {
        // Given/When: 불허 문자(.)가 포함된 room으로 접속 시도
        CollectingHandler client = new CollectingHandler();

        // Then: 인터셉터가 업그레이드 전 400으로 거절 → 핸드셰이크 실패, 엔진은 doc-id를 관측하지 못한다.
        assertThatThrownBy(() -> connect(client, "bad.room")).isInstanceOf(ExecutionException.class);
        assertThat(ENGINE.observedDocIds.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("화이트리스트에 없는 Origin의 핸드셰이크는 거부된다(네이티브 WS 유일 방어선)")
    void disallowedOrigin_rejectedAtHandshake() throws Exception {
        // Given: 허용되지 않은 Origin + 유효 room + 유효 토큰(인증이 통과해도 Origin으로 걸리는지 격리 검증)
        double okBefore = handshakeOkCount();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("https://evil.example");
        headers.setSecWebSocketProtocol(List.of(AuthSubprotocol.SENTINEL, validToken()));
        String url = "ws://localhost:" + port + "/ws/doc/demo";

        // When/Then: 인증이 유효해도 Origin 불일치로 핸드셰이크 실패 → 엔진 미관측.
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(), headers, URI.create(url))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(ENGINE.observedDocIds.poll(500, TimeUnit.MILLISECONDS)).isNull();
        // 최종 응답이 403인 핸드셰이크는 result=ok로 세지 않는다 — 앱 신호와 상태코드 정합(ADR-0021, code-review H-1).
        assertThat(handshakeOkCount()).isEqualTo(okBefore);
    }

    private double handshakeOkCount() {
        var counter = meterRegistry.find("ws.handshake").tag("result", "ok").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("토큰 없는 핸드셰이크는 인증 실패로 거절된다(업그레이드 전, 엔진 미관측)")
    void noToken_rejectedAtHandshake() throws Exception {
        // Given: 인증 서브프로토콜(토큰) 없이 유효 room으로 접속 시도
        String url = "ws://localhost:" + port + "/ws/doc/demo";

        // When/Then: auth 인터셉터가 업그레이드 전 401로 거절 → 핸드셰이크 실패, 엔진은 doc-id를 관측 못 함.
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(), url)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(ENGINE.observedDocIds.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("핸드셰이크 응답은 SENTINEL만 협상하고 토큰은 반향하지 않는다(토큰 유출 방지)")
    void handshake_echoesOnlySentinelSubprotocol() throws Exception {
        // Given/When: [SENTINEL, <jwt>]로 접속 성공
        double okBefore = handshakeOkCount();
        WebSocketSession session = connect(new CollectingHandler(), "demo");

        // Then: 서버가 협상한 서브프로토콜은 SENTINEL뿐 — 토큰은 응답 헤더로 새지 않는다(단 하나만 협상 가능).
        assertThat(session.getAcceptedProtocol()).isEqualTo(AuthSubprotocol.SENTINEL);
        // 성공 핸드셰이크는 afterHandshake(101 이후 실행)에서 result=ok로 집계된다 — H-1 수정의 정상 경로 실증(await).
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(handshakeOkCount()).isEqualTo(okBefore + 1));
    }

    private WebSocketSession connect(BinaryWebSocketHandler handler, String room) throws Exception {
        // 인증이 필수 — 유효 JWT를 Sec-WebSocket-Protocol([SENTINEL, <jwt>])로 실어 핸드셰이크를 통과시킨다.
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
    private String validToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("it-user").issuer("wedocs-doc-service")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestAuthConfig.key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(TestAuthConfig.key));
        return jwt.serialize();
    }

    /// WS 클라이언트가 받은 바이너리 메시지를 모은다.
    private static final class CollectingHandler extends BinaryWebSocketHandler {
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
            return JwtVerifier.fromKeySource(keySource, "wedocs-doc-service", java.time.Duration.ofSeconds(60));
        }
    }

    /// 엔진 대역. doc-id 메타데이터를 기록하고, 받은 update를 모든 스트림에 broadcast(self-echo 포함, §D-3).
    private static final class FakeCrdtEngine extends CrdtEngineGrpc.CrdtEngineImplBase {

        private static final Metadata.Key<String> DOC_ID_KEY =
                Metadata.Key.of("doc-id", Metadata.ASCII_STRING_MARSHALLER);

        final BlockingQueue<String> observedDocIds = new LinkedBlockingQueue<>();
        final BlockingQueue<ClientFrame> receivedFrames = new LinkedBlockingQueue<>();
        final BlockingQueue<String> completedStreams = new LinkedBlockingQueue<>();
        private final Set<StreamObserver<ServerFrame>> observers = ConcurrentHashMap.newKeySet();
        private final Object connectSignal = new Object();
        // 응답 observer 접근을 직렬화한다(grpc-java: 동시 호출 금지). grpc가 소유한 observer 객체를
        // 락으로 쓰지 않도록 별도 ReentrantLock 사용(락 순서 의존성 회피).
        private final ReentrantLock streamLock = new ReentrantLock();
        private Server server;

        int startOnRandomPort() {
            try {
                server = ServerBuilder.forPort(0)
                        .addService(ServerInterceptors.intercept(this, docIdInterceptor()))
                        .build()
                        .start();
                return server.getPort();
            } catch (IOException e) {
                throw new IllegalStateException("fake engine 기동 실패", e);
            }
        }

        void stop() {
            if (server != null) {
                server.shutdownNow();
            }
        }

        void reset() {
            observedDocIds.clear();
            receivedFrames.clear();
            completedStreams.clear();
            observers.clear();
        }

        void awaitObservers(int n, long timeoutMs) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            synchronized (connectSignal) {
                while (observers.size() < n) {
                    long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainMs <= 0) {
                        throw new AssertionError("engine 스트림 " + n + "개 대기 타임아웃, 현재=" + observers.size());
                    }
                    connectSignal.wait(remainMs);
                }
            }
        }

        @Override
        public StreamObserver<ClientFrame> sync(StreamObserver<ServerFrame> responseObserver) {
            observers.add(responseObserver);
            synchronized (connectSignal) {
                connectSignal.notifyAll();
            }
            return new StreamObserver<>() {
                @Override
                public void onNext(ClientFrame frame) {
                    receivedFrames.add(frame);
                    if (!frame.getUpdate().isEmpty()) {
                        broadcast(ServerFrame.newBuilder().setUpdate(frame.getUpdate()).build());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    observers.remove(responseObserver);
                }

                @Override
                public void onCompleted() {
                    observers.remove(responseObserver);
                    streamLock.lock();
                    try {
                        responseObserver.onCompleted();
                    } finally {
                        streamLock.unlock();
                    }
                    completedStreams.add("completed");
                }
            };
        }

        private void broadcast(ServerFrame frame) {
            streamLock.lock();
            try {
                for (StreamObserver<ServerFrame> observer : observers) {
                    observer.onNext(frame);
                }
            } finally {
                streamLock.unlock();
            }
        }

        private ServerInterceptor docIdInterceptor() {
            return new ServerInterceptor() {
                @Override
                public <Q, P> ServerCall.Listener<Q> interceptCall(
                        ServerCall<Q, P> call, Metadata headers, ServerCallHandler<Q, P> next) {
                    String docId = headers.get(DOC_ID_KEY);
                    if (docId != null) {
                        observedDocIds.add(docId);
                    }
                    return next.startCall(call, headers);
                }
            };
        }
    }
}

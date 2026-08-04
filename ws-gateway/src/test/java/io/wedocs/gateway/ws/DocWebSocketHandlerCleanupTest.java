package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.grpc.EngineClient;
import io.wedocs.gateway.grpc.EngineProperties;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// WebSocket 세션 정리(cleanup) 경로 검증 — 정상 종료, 에러 종료, 이중 종료, 엔진 스트림 에러, send 실패.
/// bridges 맵이 비워지고, toEngine.onCompleted()가 호출되며, sessionMetrics.sessionClosed()가 정확히
/// 1회 증분되는지를 각 경로별로 확인한다. (Requirements 11.1, 11.4)
class DocWebSocketHandlerCleanupTest {

    private static final String ROOM = "test-room-cleanup";

    private SpyEngineClient engineClient;
    private SimpleMeterRegistry registry;
    private DocWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        engineClient = new SpyEngineClient();
        registry = new SimpleMeterRegistry();
        handler = new DocWebSocketHandler(engineClient, new SessionMetrics(registry));
    }

    // ─── 시나리오 1: 정상 종료 ───

    @Test
    @DisplayName("afterConnectionClosed → toEngine.onCompleted 호출, sessionClosed 1회 증분")
    void normalClose_completesStreamAndIncrementsMetric() {
        // Given
        CleanupSession session = openSession();

        // When
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Then
        assertThat(engineClient.spy.completed).isTrue();
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("afterConnectionClosed 후 handleBinaryMessage는 무시된다 (bridge 이미 제거)")
    void normalClose_subsequentMessagesIgnored() throws Exception {
        // Given
        CleanupSession session = openSession();
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // When — 세션 닫힌 뒤 메시지 도착 (비정상이지만 방어)
        byte[] syncUpdate = {0x00, 0x02, 0x02, 0x55, 0x66};
        handler.handleMessage(session, new BinaryMessage(syncUpdate));

        // Then — 엔진에 프레임이 전달되지 않는다
        assertThat(engineClient.spy.nextCount).isZero();
    }

    // ─── 시나리오 2: 전송 에러 (handleTransportError) ───

    @Test
    @DisplayName("handleTransportError → toEngine.onCompleted 호출, sessionClosed 증분")
    void transportError_completesStreamAndIncrementsMetric() {
        // Given
        CleanupSession session = openSession();

        // When
        handler.handleTransportError(session, new IOException("connection reset"));

        // Then
        assertThat(engineClient.spy.completed).isTrue();
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("handleTransportError 후 afterConnectionClosed가 이어져도 이중 정리 없음")
    void transportError_thenAfterConnectionClosed_noDoubleCleanup() {
        // Given
        CleanupSession session = openSession();

        // When — Spring 스펙상 afterConnectionClosed가 이어진다
        handler.handleTransportError(session, new IOException("reset"));
        handler.afterConnectionClosed(session, CloseStatus.SESSION_NOT_RELIABLE);

        // Then — completeQuietly는 1회만, 메트릭도 1회만
        assertThat(engineClient.spy.completedCount).isEqualTo(1);
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    // ─── 시나리오 3: 이중 종료 (endSession → afterConnectionClosed) ───

    @Test
    @DisplayName("engineResponseObserver.onError → endSession, 이후 afterConnectionClosed는 no-op")
    void doubleClose_endSessionThenAfterConnectionClosed_noDoubleCleanup() {
        // Given
        CleanupSession session = openSession();
        StreamObserver<ServerFrame> responseObserver = engineClient.capturedResponseObserver;

        // When — 엔진이 에러로 스트림 종료 → endSession 호출
        responseObserver.onError(new RuntimeException("engine crash"));
        // Spring은 이어서 afterConnectionClosed를 호출한다
        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        // Then — 두 번째 close 경로는 no-op
        assertThat(engineClient.spy.completedCount).isEqualTo(1);
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    // ─── 시나리오 4: 엔진 스트림 에러 ───

    @Test
    @DisplayName("engineResponseObserver.onError → WS close 호출, toEngine.onCompleted 호출")
    void engineStreamError_closesWsAndCompletesStream() {
        // Given
        CleanupSession session = openSession();
        StreamObserver<ServerFrame> responseObserver = engineClient.capturedResponseObserver;

        // When
        responseObserver.onError(new RuntimeException("engine unavailable"));

        // Then
        assertThat(session.closeStatus).isNotNull();
        assertThat(session.closeStatus.getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        assertThat(engineClient.spy.completed).isTrue();
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("engineResponseObserver.onCompleted → WS 정상 종료")
    void engineStreamCompleted_closesWsNormally() {
        // Given
        CleanupSession session = openSession();
        StreamObserver<ServerFrame> responseObserver = engineClient.capturedResponseObserver;

        // When
        responseObserver.onCompleted();

        // Then
        assertThat(session.closeStatus).isNotNull();
        assertThat(session.closeStatus.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
        assertThat(engineClient.spy.completed).isTrue();
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    // ─── 시나리오 5: sendBinary 실패 ───

    @Test
    @DisplayName("sendBinary IOException → endSession → WS close + toEngine.onCompleted")
    void sendFailure_closesSessionAndCompletesStream() {
        // Given
        CleanupSession session = openSession();
        session.failOnSend = true; // sendMessage 시 IOException 발생하게 설정
        StreamObserver<ServerFrame> responseObserver = engineClient.capturedResponseObserver;

        // When — 엔진이 프레임을 보낸다 → sendBinary → IOException → endSession
        ServerFrame frame = ServerFrame.newBuilder()
                .setUpdate(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();
        responseObserver.onNext(frame);

        // Then
        assertThat(session.closeStatus).isNotNull();
        assertThat(session.closeStatus.getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        assertThat(engineClient.spy.completed).isTrue();
        assertThat(sessionClosedCount()).isEqualTo(1);
    }

    // ─── 시나리오 6: openSync 실패 ───

    @Test
    @DisplayName("openSync 예외 → 세션 닫힘, bridge 미등록, sessionClosed 증분 없음")
    void openSyncFailure_closesSessionWithoutBridgeRegistration() {
        // Given
        engineClient.failOnOpen = true;
        CleanupSession session = newSession();

        // When
        handler.afterConnectionEstablished(session);

        // Then — 세션이 닫히지만 bridge가 등록된 적 없으므로 sessionClosed 미호출
        assertThat(session.closeStatus).isNotNull();
        assertThat(session.closeStatus.getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        assertThat(sessionClosedCount()).isZero();
    }

    @Test
    @DisplayName("openSync 실패 후 afterConnectionClosed가 호출되어도 안전하다 (bridge 없음)")
    void openSyncFailure_afterConnectionClosedIsNoOp() {
        // Given
        engineClient.failOnOpen = true;
        CleanupSession session = newSession();
        handler.afterConnectionEstablished(session);

        // When
        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        // Then — bridge가 없으므로 추가 정리 불필요, 메트릭도 미증분
        assertThat(sessionClosedCount()).isZero();
    }

    // ─── 헬퍼 ───

    private double sessionClosedCount() {
        var counter = registry.find(SessionMetrics.SESSION_CLOSED).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private CleanupSession openSession() {
        CleanupSession session = newSession();
        handler.afterConnectionEstablished(session);
        return session;
    }

    private CleanupSession newSession() {
        CleanupSession session = new CleanupSession();
        session.getAttributes().put(HandshakeAttributes.ROOM_ATTRIBUTE, new RoomId(ROOM));
        session.getAttributes().put(SessionRole.ATTRIBUTE, SessionRole.EDITOR);
        return session;
    }

    // ─── 테스트 대역 ───

    /// 엔진 클라이언트 스파이 — openSync가 반환하는 StreamObserver를 추적하고, 응답 observer를 캡처한다.
    private static final class SpyEngineClient extends EngineClient {

        StreamObserver<ServerFrame> capturedResponseObserver;
        final SpyStreamObserver spy = new SpyStreamObserver();
        boolean failOnOpen;

        SpyEngineClient() {
            super(new EngineProperties("localhost:1"));
        }

        @Override
        public StreamObserver<ClientFrame> openSync(
                String docId, String role, StreamObserver<ServerFrame> responseObserver) {
            if (failOnOpen) {
                throw new RuntimeException("engine unavailable");
            }
            this.capturedResponseObserver = responseObserver;
            return spy;
        }
    }

    /// toEngine StreamObserver 스파이 — onNext/onCompleted 호출 횟수를 추적한다.
    private static final class SpyStreamObserver implements StreamObserver<ClientFrame> {

        int nextCount;
        int completedCount;
        boolean completed;

        @Override
        public void onNext(ClientFrame value) {
            nextCount++;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
            completedCount++;
            completed = true;
        }
    }

    /// WebSocketSession 스텁 — send 실패 시나리오를 지원한다.
    private static final class CleanupSession implements WebSocketSession {

        private final String id = "cleanup-session-" + System.nanoTime();
        private final Map<String, Object> attributes = new HashMap<>();
        CloseStatus closeStatus;
        boolean open = true;
        boolean failOnSend;

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public void close(CloseStatus status) {
            this.closeStatus = status;
            this.open = false;
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (failOnSend) {
                throw new IOException("simulated send failure");
            }
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/doc/" + ROOM);
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return org.springframework.http.HttpHeaders.EMPTY;
        }

        @Override
        public Principal getPrincipal() {
            return () -> "test-user";
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }
    }
}

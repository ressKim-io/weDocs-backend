package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.grpc.EngineClient;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// 세션 핸들러의 인가 집행 검증 (ADR-0014 D-5 1차 방어). 인가 규칙이 걸린 클래스라 Spring 컨텍스트 없이
/// 직접 검증한다 — 통합 테스트만으로는 경계 케이스(빈 update·role 부재)를 겨냥하기 어렵고 느리다.
class DocWebSocketHandlerTest {

    /// y-protocols 와이어: [messageSync=0][syncType][len][payload...]
    private static final byte[] SYNC_UPDATE = {0x00, 0x02, 0x02, 0x55, 0x66};
    private static final byte[] SYNC_STEP1 = {0x00, 0x00, 0x03, 1, 2, 3};
    /// SyncUpdate인데 payload 길이 0 — 코덱이 update를 빈 ByteString으로 채운다.
    private static final byte[] SYNC_UPDATE_EMPTY = {0x00, 0x02, 0x00};

    private RecordingEngineClient engineClient;
    private SimpleMeterRegistry registry;
    private DocWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        engineClient = new RecordingEngineClient();
        registry = new SimpleMeterRegistry();
        handler = new DocWebSocketHandler(engineClient, new SessionMetrics(registry));
    }

    @Test
    @DisplayName("editor 세션의 update는 엔진으로 전달된다")
    void editor_forwardsUpdate() throws Exception {
        StubSession session = openSession(SessionRole.EDITOR);

        handler.handleMessage(session, new BinaryMessage(SYNC_UPDATE));

        assertThat(engineClient.sent).hasSize(1);
        assertThat(engineClient.sent.getFirst().getUpdate().toByteArray()).containsExactly(0x55, 0x66);
        assertThat(droppedCount()).isZero();
    }

    @Test
    @DisplayName("viewer 세션의 update는 엔진에 전달되지 않고 드롭이 계측된다")
    void viewer_dropsUpdate() throws Exception {
        StubSession session = openSession(SessionRole.VIEWER);

        handler.handleMessage(session, new BinaryMessage(SYNC_UPDATE));

        assertThat(engineClient.sent).isEmpty();
        assertThat(droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("viewer 세션의 SyncStep1(읽기 요청)은 통과한다 — 막으면 읽기 자체가 불가능해진다")
    void viewer_forwardsSyncStep1() throws Exception {
        StubSession session = openSession(SessionRole.VIEWER);

        handler.handleMessage(session, new BinaryMessage(SYNC_STEP1));

        assertThat(engineClient.sent).hasSize(1);
        assertThat(engineClient.sent.getFirst().getStateVector().toByteArray()).containsExactly(1, 2, 3);
        assertThat(droppedCount()).isZero();
    }

    @Test
    @DisplayName("viewer의 빈 update는 통과하지만 문서를 바꿀 수 없다(엔진도 빈 update에 apply를 건너뛴다)")
    void viewer_emptyUpdatePassesButCannotMutate() throws Exception {
        // 판정 술어가 payload 유무이므로 빈 update는 통과한다. 이것이 안전한 이유는 빈 update가
        // 양쪽 모두에서 no-op이기 때문 — "통과하지만 무해"임을 명시적으로 고정해 둔다.
        StubSession session = openSession(SessionRole.VIEWER);

        handler.handleMessage(session, new BinaryMessage(SYNC_UPDATE_EMPTY));

        assertThat(engineClient.sent).hasSize(1);
        assertThat(engineClient.sent.getFirst().getUpdate()).isEqualTo(ByteString.EMPTY);
    }

    @Test
    @DisplayName("role attribute가 없으면 엔진 스트림을 열지 않고 세션을 닫는다(fail-closed)")
    void missingRole_closesSessionWithoutOpeningStream() {
        // 권한을 모른 채 스트림을 열면 viewer가 editor로 취급된다 — 인터셉터 미배선 시의 안전망.
        StubSession session = new StubSession();
        session.getAttributes().put(RoomHandshakeInterceptor.ROOM_ATTRIBUTE, new RoomId(roomUuid()));

        handler.afterConnectionEstablished(session);

        assertThat(engineClient.opened).isFalse();
        assertThat(session.closeStatus).isNotNull();
        assertThat(session.closeStatus.getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
    }

    @Test
    @DisplayName("room attribute가 없으면 마찬가지로 스트림을 열지 않는다")
    void missingRoom_closesSessionWithoutOpeningStream() {
        StubSession session = new StubSession();
        session.getAttributes().put(SessionRole.ATTRIBUTE, SessionRole.EDITOR);

        handler.afterConnectionEstablished(session);

        assertThat(engineClient.opened).isFalse();
        assertThat(session.closeStatus).isNotNull();
    }

    @Test
    @DisplayName("세션 role은 엔진 open 시 wire 값으로 전달된다(2b가 소비할 입력)")
    void role_isPassedToEngineOnOpen() {
        openSession(SessionRole.VIEWER);

        assertThat(engineClient.role).isEqualTo("viewer");
    }

    private double droppedCount() {
        var counter = registry.find(SessionMetrics.WRITE_DROPPED).tag("reason", "viewer").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String roomUuid() {
        return "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    }

    private StubSession openSession(SessionRole role) {
        StubSession session = new StubSession();
        session.getAttributes().put(RoomHandshakeInterceptor.ROOM_ATTRIBUTE, new RoomId(roomUuid()));
        session.getAttributes().put(SessionRole.ATTRIBUTE, role);
        handler.afterConnectionEstablished(session);
        return session;
    }

    /// 엔진 클라이언트 대역 — 전달된 프레임과 open 시 메타데이터를 기록한다.
    /// `EngineClient`는 생성자에서 gRPC 채널을 열므로 상속 대신 super(target)로 로컬 주소를 준다:
    /// grpc-java 채널은 지연 연결이라 실제 접속 없이 생성만 되고, openSync를 override해 네트워크를 타지 않는다.
    private static final class RecordingEngineClient extends EngineClient {

        private final List<ClientFrame> sent = new ArrayList<>();
        private boolean opened;
        private String role;

        private RecordingEngineClient() {
            super("localhost:1"); // 연결하지 않는다 — openSync를 완전히 대체하므로 채널은 미사용.
        }

        @Override
        public StreamObserver<ClientFrame> openSync(
                String docId, String role, StreamObserver<ServerFrame> responseObserver) {
            this.opened = true;
            this.role = role;
            return new StreamObserver<>() {
                @Override
                public void onNext(ClientFrame frame) {
                    sent.add(frame);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }
    }

    /// WebSocketSession 최소 대역 — 핸들러가 실제로 쓰는 것은 id·attributes·close뿐이다.
    private static final class StubSession implements WebSocketSession {

        private final Map<String, Object> attributes = new HashMap<>();
        private CloseStatus closeStatus;
        private boolean open = true;

        @Override
        public String getId() {
            return "stub-session";
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
        public void sendMessage(WebSocketMessage<?> message) {
            throw new UnsupportedOperationException("이 테스트는 인바운드 경로만 다룬다");
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/doc/" + roomUuid());
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return org.springframework.http.HttpHeaders.EMPTY;
        }

        @Override
        public Principal getPrincipal() {
            return () -> "stub";
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

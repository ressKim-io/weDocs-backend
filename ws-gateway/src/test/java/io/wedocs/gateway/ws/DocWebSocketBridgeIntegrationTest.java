package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.CrdtEngineGrpc;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/// raw WS 클라이언트 ↔ gateway ↔ fake in-process gRPC 엔진 종단 검증.
/// 검증: (1) doc-id 메타데이터 전파 + 인바운드 SyncStep1 forward, (2) ServerFrame{update} → WS Update(2) fan-out(2클라).
/// 실제 Rust 엔진 수렴은 로컬 스모크 + Phase 3 E2E가 담당(여기선 와이어↔gRPC 브리지 배선만 결정적으로 검증).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocWebSocketBridgeIntegrationTest {

    private static final long TIMEOUT_MS = 5_000;

    private static final FakeCrdtEngine ENGINE = new FakeCrdtEngine();

    @LocalServerPort
    private int port;

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

        session.close();
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

        sessionA.close();
        sessionB.close();
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

    private WebSocketSession connect(BinaryWebSocketHandler handler, String room) throws Exception {
        String url = "ws://localhost:" + port + "/ws/doc/" + room;
        return new StandardWebSocketClient()
                .execute(handler, url)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

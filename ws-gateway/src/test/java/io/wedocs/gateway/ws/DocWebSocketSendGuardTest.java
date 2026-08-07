package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/// 아웃바운드 송신 경로가 `ConcurrentWebSocketSessionDecorator`를 통과하는지, 그리고 그 데코레이터가
/// 새로 만든 실패 모드(송신 상한 초과)를 전송 오류와 **구분해서** 처리하는지 검증한다.
/// (M3 plan §1.1 — awareness fan-out보다 먼저 들어가야 하는 커밋)
class DocWebSocketSendGuardTest {

    private static final String ROOM = "11111111-1111-4111-8111-111111111111";
    /// [messageAwareness=1, varBuffer({0xAA,0xBB})]
    private static final byte[] AWARENESS_FRAME = {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
    /// 블로킹 어서션 대기 — "막혔다/안 막혔다"를 가르는 값이라 정상 경로보다 넉넉히.
    private static final long BLOCK_TIMEOUT_MS = 5_000;

    private StubEngineClient engineClient;
    private SimpleMeterRegistry registry;
    private DocWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        engineClient = new StubEngineClient();
        registry = new SimpleMeterRegistry();
        handler = new DocWebSocketHandler(engineClient, new SessionMetrics(registry));
    }

    @Test
    @DisplayName("게이트웨이가 세션에 직접 쓰지 않는다 — 아웃바운드는 데코레이터를 통과해 delegate에 도달")
    void outboundFrame_passesThroughDecorator_toDelegate() {
        // Given: 세션 수립 — 핸들러는 원본이 아니라 감싼 세션을 들고 있어야 한다
        RecordingWsSession session = openSession();

        // When: 엔진이 update를 내려보낸다(엔진 → 브라우저 방향)
        engineClient.latest().toClient().onNext(
                ServerFrame.newBuilder().setUpdate(ByteString.copyFrom(new byte[]{0x55, 0x66})).build());

        // Then: 프레임이 delegate까지 도달한다(데코레이터가 큐잉만 하고 삼키지 않는다)
        assertThat(session.sent).containsExactly(new byte[]{0x00, 0x02, 0x02, 0x55, 0x66});
    }

    @Test
    @DisplayName("한 대상에 대한 동시 fan-out은 delegate에 겹쳐 들어가지 않고 두 번째 발신자를 막지 않는다")
    void concurrentFanOutToSameTarget_isQueuedNotBlocked() throws Exception {
        // 이 테스트가 데코레이터 배선을 **죽인다**: guardSends를 없애고 원본 세션을 쓰면 두 번째
        // 발신 스레드도 delegate의 같은 지점에서 막혀 `second.isAlive()` 어서션이 실패한다.
        // Given: 느린 대상 T + 발신자 2명(같은 룸)
        RecordingWsSession target = openSession();
        RecordingWsSession senderA = openSession();
        RecordingWsSession senderB = openSession();
        clearSent(target, senderA, senderB);
        target.blockSends = true;

        // When: A의 fan-out이 T의 delegate 안에서 멈춘다(그 스레드가 데코레이터 flush 락을 쥔다)
        Thread first = new Thread(() -> relayQuietly(senderA));
        first.start();
        assertThat(target.awaitEntered(BLOCK_TIMEOUT_MS))
                .as("A의 fan-out이 T의 delegate에 진입해야 한다")
                .isTrue();

        // Then: B의 fan-out은 막히지 않고 반환한다 — 데코레이터가 큐에 넣기 때문이다.
        Thread second = new Thread(() -> relayQuietly(senderB));
        second.start();
        second.join(BLOCK_TIMEOUT_MS);
        assertThat(second.isAlive())
                .as("B의 fan-out이 T의 느린 delegate에 붙잡혔다 — 데코레이터가 송신 경로에 없다")
                .isFalse();

        // And: delegate 진입이 겹치지 않았고, 큐에 쌓인 프레임도 유실되지 않는다.
        target.releaseSends();
        first.join(BLOCK_TIMEOUT_MS);
        assertThat(target.maxConcurrentEntries())
                .as("delegate에 동시 진입이 발생했다 — WS 동시 send 계약 위반")
                .isEqualTo(1);
        assertThat(target.sent).hasSize(2);
    }

    @Test
    @DisplayName("핸들러가 감싸는 대상은 ConcurrentWebSocketSessionDecorator이고 상한이 배선돼 있다")
    void establishedSession_isWrappedWithConfiguredLimits() {
        // Given/When: production factory로 감싸 상한과 acceptance callback 배선을 함께 고정한다.
        RecordingWsSession delegate = new RecordingWsSession();
        WebSocketSession guarded = handler.guardSends(delegate);

        // Then: 송신 큐 상한이 최대 아웃바운드 프레임(4MiB)보다 크다 —
        // 이보다 작으면 느린 클라이언트가 *정상* 초기 동기화 중에 끊긴다.
        assertThat(((ConcurrentWebSocketSessionDecorator) guarded).getBufferSizeLimit())
                .isGreaterThan(4 * 1024 * 1024);
        assertThat(((ConcurrentWebSocketSessionDecorator) guarded).getSendTimeLimit())
                .isEqualTo(DocWebSocketHandler.SEND_TIME_LIMIT_MS);
    }

    @Test
    @DisplayName("terminal decorator의 무음 거절은 송신 수락으로 집계하지 않는다")
    void terminalDecorator_silentRejectionIsNotAccepted() throws Exception {
        // Given: 정상 close된 decorator는 이후 sendMessage를 예외 없이 무시한다.
        RecordingWsSession delegate = new RecordingWsSession();
        WebSocketSession guarded = handler.guardSends(delegate);
        guarded.close(CloseStatus.NORMAL);

        // When/Then: 정상 반환 자체가 아니라 message callback이 실행돼야 acceptance=true다.
        assertThat(handler.sendBinary(guarded, new byte[]{0x01})).isFalse();
        assertThat(delegate.sent).isEmpty();
        assertThat(counter(SessionMetrics.SEND_LIMIT_EXCEEDED)).isZero();
    }

    @Test
    @DisplayName("송신 상한 초과는 세션을 끊고 ws.send.limit.exceeded로 집계된다(전송 오류와 구분)")
    void sendLimitExceeded_closesSessionAndCountsAggregateLimit() {
        // Given: delegate가 데코레이터 상한 초과 예외를 던지는 세션
        RecordingWsSession session = openSession();
        session.failMode = RecordingWsSession.FailMode.LIMIT;

        // When: 엔진이 프레임을 내려보낸다
        engineClient.latest().toClient().onNext(
                ServerFrame.newBuilder().setUpdate(ByteString.copyFrom(new byte[]{0x01})).build());

        // Then: 세션 종료 + aggregate 카운터 증분 + 엔진 스트림 정리(누수 방지)
        assertThat(session.closeStatus).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(counter(SessionMetrics.SEND_LIMIT_EXCEEDED)).isEqualTo(1.0);
        assertThat(counter(SessionMetrics.SESSION_CLOSED)).isEqualTo(1.0);
        assertThat(engineClient.latest().toEngine().completedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("전송 오류(IOException)는 송신 상한 초과로 집계되지 않는다 — 두 실패 모드의 구분 회귀")
    void sendIoFailure_doesNotCountAsLimitExceeded() {
        // Given: delegate가 전송 계층 오류를 던지는 세션
        RecordingWsSession session = openSession();
        session.failMode = RecordingWsSession.FailMode.IO;

        // When
        engineClient.latest().toClient().onNext(
                ServerFrame.newBuilder().setUpdate(ByteString.copyFrom(new byte[]{0x01})).build());

        // Then: 세션은 끊기지만 상한 카운터는 오르지 않는다(원인 오분류 방지)
        assertThat(session.closeStatus).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(counter(SessionMetrics.SEND_LIMIT_EXCEEDED)).isZero();
        assertThat(counter(SessionMetrics.SESSION_CLOSED)).isEqualTo(1.0);
    }

    // ─── 헬퍼 ───

    private RecordingWsSession openSession() {
        RecordingWsSession session = new RecordingWsSession();
        session.getAttributes().put(HandshakeAttributes.ROOM_ATTRIBUTE, new RoomId(ROOM));
        session.getAttributes().put(SessionRole.ATTRIBUTE, SessionRole.EDITOR);
        handler.afterConnectionEstablished(session);
        return session;
    }

    /// awareness 프레임을 넣어 fan-out을 유발한다(별 스레드에서 호출되므로 예외를 삼키지 않고 감싼다).
    private void relayQuietly(RecordingWsSession sender) {
        try {
            handler.handleMessage(sender, new BinaryMessage(AWARENESS_FRAME));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void clearSent(RecordingWsSession... sessions) {
        for (RecordingWsSession session : sessions) {
            session.sent.clear();
        }
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

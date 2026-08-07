package io.wedocs.gateway.ws;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/// awareness 관측 계약. (M3 plan §1.6)
///
/// 이 계측이 없으면 릴레이·발신이 **조용히 죽는다** — 두 경로 모두 실패해도 에러가 나지 않고
/// "커서가 조금 늦게 보인다"로만 나타나기 때문이다(secure-coding: 무신호 실패 금지).
class DocWebSocketAwarenessMetricsTest {

    private static final RoomId ROOM_A = new RoomId("11111111-1111-4111-8111-111111111111");
    private static final RoomId ROOM_B = new RoomId("22222222-2222-4222-8222-222222222222");

    private static final byte[] AWARENESS_FRAME = {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};

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
    @DisplayName("릴레이는 프레임 수가 아니라 전달된 대상 수(fan-out 간선)를 센다")
    void relayed_countsDeliveredTargets_notFrames() throws Exception {
        // Given: 같은 룸 4세션(발신자 + peer 3)
        RecordingWsSession sender = openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);

        // When: 프레임 1개 송신
        handler.handleMessage(sender, new BinaryMessage(AWARENESS_FRAME));

        // Then: 프레임 1개가 아니라 간선 3개로 집계된다 — 룸이 커질수록 프레임 하나의 비용이 N배다
        assertThat(counter(SessionMetrics.AWARENESS_RELAYED)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("peer가 없으면 릴레이 카운터는 0이지만 메터는 등록된다(계열 누락 방지)")
    void relayed_withNoPeers_registersMeterAtZero() throws Exception {
        RecordingWsSession solo = openSession(ROOM_A, SessionRole.EDITOR);

        handler.handleMessage(solo, new BinaryMessage(AWARENESS_FRAME));

        assertThat(registry.find(SessionMetrics.AWARENESS_RELAYED).counter()).isNotNull();
        assertThat(counter(SessionMetrics.AWARENESS_RELAYED)).isZero();
    }

    @Test
    @DisplayName("join 시 발신한 queryAwareness 수가 집계된다 — 0이면 §1.3 배선이 죽은 것")
    void querySent_countsPeersQueriedOnJoin() {
        // Given/When: 세션 3개가 차례로 붙는다.
        // 1번째 join → peer 0명, 2번째 → 1명, 3번째 → 2명 ⇒ 누적 3
        openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);

        assertThat(counter(SessionMetrics.AWARENESS_QUERY_SENT)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("상한 초과 드롭은 reason=awareness_too_large로 집계되고 error.type과 값이 같다")
    void dropped_tooLarge_isTaggedConsistentlyWithErrorType() throws Exception {
        RecordingWsSession sender = openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);

        handler.handleMessage(sender, new BinaryMessage(
                awarenessFrame(new byte[DocWebSocketHandler.MAX_AWARENESS_PAYLOAD_BYTES + 1])));

        assertThat(droppedCount(SessionMetrics.REASON_TOO_LARGE)).isEqualTo(1.0);
        // 로그(error.type)와 메트릭(reason)이 같은 문자열이어야 대시보드에서 상관된다
        assertThat(SessionMetrics.REASON_TOO_LARGE).isEqualTo("awareness_too_large");
        // 드롭된 프레임은 릴레이로 집계되지 않는다
        assertThat(counter(SessionMetrics.AWARENESS_RELAYED)).isZero();
    }

    @Test
    @DisplayName("전송 실패한 대상은 릴레이로 집계되지 않는다 — 처리량 지표가 거짓말하지 않도록")
    void relayed_excludesFailedSends() throws Exception {
        RecordingWsSession sender = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession healthy = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession broken = openSession(ROOM_A, SessionRole.EDITOR);
        broken.failMode = RecordingWsSession.FailMode.IO;

        handler.handleMessage(sender, new BinaryMessage(AWARENESS_FRAME));

        assertThat(counter(SessionMetrics.AWARENESS_RELAYED)).isEqualTo(1.0);
        assertThat(healthy.sent).isNotEmpty();
        // 실패한 대상만 끊긴다 — 발신자와 정상 peer는 영향받지 않는다
        assertThat(broken.closeStatus).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(sender.closeStatus).isNull();
        assertThat(healthy.closeStatus).isNull();
    }

    @Test
    @DisplayName("룸 점유 게이지가 최대 룸 크기와 활성 룸 수를 노출한다")
    void roomGauges_exposeMaxOccupancyAndActiveRooms() {
        openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession soloInB = openSession(ROOM_B, SessionRole.EDITOR);

        assertThat(gauge(SessionMetrics.ROOM_SESSIONS_MAX)).isEqualTo(2.0);
        assertThat(gauge(SessionMetrics.ROOMS_ACTIVE)).isEqualTo(2.0);

        // 세션이 떠나면 게이지가 따라 내려간다(누수 관측)
        handler.afterConnectionClosed(soloInB, CloseStatus.NORMAL);

        assertThat(gauge(SessionMetrics.ROOMS_ACTIVE)).isEqualTo(1.0);
        assertThat(gauge(SessionMetrics.ROOM_SESSIONS_MAX)).isEqualTo(2.0);
    }

    // ─── 헬퍼 ───

    private RecordingWsSession openSession(RoomId room, SessionRole role) {
        RecordingWsSession session = new RecordingWsSession();
        session.getAttributes().put(HandshakeAttributes.ROOM_ATTRIBUTE, room);
        session.getAttributes().put(SessionRole.ATTRIBUTE, role);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private static byte[] awarenessFrame(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, YProtocolCodec.MESSAGE_AWARENESS);
        Lib0.writeVarUint8Array(out, payload);
        return out.toByteArray();
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double droppedCount(String reason) {
        var counter = registry.find(SessionMetrics.AWARENESS_DROPPED).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double gauge(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? -1.0 : gauge.value();
    }
}

package io.wedocs.gateway.ws;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import io.wedocs.proto.crdt.ClientFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/// awareness 룸 릴레이 + join 시 queryAwareness 발신. (M3 plan §1.3 · §1.4)
///
/// Spring 컨텍스트 없이 핸들러를 직접 구동한다 — 검증 대상이 라우팅 결정(누구에게 보내고 누구에게
/// 안 보내는가)이라 전송 계층이 필요하지 않다.
class DocWebSocketAwarenessTest {

    private static final RoomId ROOM_A = new RoomId("11111111-1111-4111-8111-111111111111");
    private static final RoomId ROOM_B = new RoomId("22222222-2222-4222-8222-222222222222");

    /// [messageAwareness=1, varBuffer({0xAA,0xBB})]
    private static final byte[] AWARENESS_FRAME = {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
    /// [messageSync=0, Update=2, varBuffer({0x55,0x66})]
    private static final byte[] SYNC_UPDATE_FRAME = {0x00, 0x02, 0x02, 0x55, 0x66};
    /// [messageQueryAwareness=3] — 페이로드 없음
    private static final byte[] QUERY_AWARENESS_FRAME = {0x03};

    private StubEngineClient engineClient;
    private SimpleMeterRegistry registry;
    private DocWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        engineClient = new StubEngineClient();
        registry = new SimpleMeterRegistry();
        handler = new DocWebSocketHandler(engineClient, new SessionMetrics(registry));
    }

    // ─── 릴레이 ───

    @Test
    @DisplayName("awareness는 같은 룸의 다른 세션에 릴레이되고 발신자에게는 되돌아오지 않는다(self-echo 제외)")
    void awareness_relayedToRoomPeers_withoutSelfEcho() throws Exception {
        // Given: 같은 룸 3세션
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession c = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b, c);

        // When: A가 awareness 송신
        handler.handleMessage(a, new BinaryMessage(AWARENESS_FRAME));

        // Then: B·C만 받는다. 프레임은 재인코딩되지만 페이로드는 바이트 그대로다.
        assertThat(b.sent).containsExactly(AWARENESS_FRAME);
        assertThat(c.sent).containsExactly(AWARENESS_FRAME);
        assertThat(a.sent).isEmpty();
    }

    @Test
    @DisplayName("awareness는 엔진으로 forward되지 않는다(판단 1 — 엔진을 통과하지 않는다)")
    void awareness_isNotForwardedToEngine() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        openSession(ROOM_A, SessionRole.EDITOR);

        handler.handleMessage(a, new BinaryMessage(AWARENESS_FRAME));

        assertThat(engineClient.opened.getFirst().toEngine().frames).isEmpty();
    }

    @Test
    @DisplayName("awareness는 룸을 넘지 않는다 — 다른 doc의 세션은 받지 않는다")
    void awareness_doesNotCrossRooms() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession other = openSession(ROOM_B, SessionRole.EDITOR);
        drainQueryAwareness(a, other);

        handler.handleMessage(a, new BinaryMessage(AWARENESS_FRAME));

        assertThat(other.sent).isEmpty();
    }

    @Test
    @DisplayName("viewer의 awareness는 릴레이된다 — 읽는 사람의 커서가 보이는 것은 정상 동작(§1.4)")
    void viewerAwareness_isRelayed() throws Exception {
        RecordingWsSession viewer = openSession(ROOM_A, SessionRole.VIEWER);
        RecordingWsSession editor = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(viewer, editor);

        handler.handleMessage(viewer, new BinaryMessage(AWARENESS_FRAME));

        assertThat(editor.sent).containsExactly(AWARENESS_FRAME);
        assertThat(counter(SessionMetrics.WRITE_DROPPED)).isZero();
    }

    @Test
    @DisplayName("viewer의 update는 여전히 엔진으로 가지 않는다 — 인가 회귀(awareness 허용이 쓰기를 열지 않는다)")
    void viewerUpdate_isStillDropped() throws Exception {
        RecordingWsSession viewer = openSession(ROOM_A, SessionRole.VIEWER);
        drainQueryAwareness(viewer);

        handler.handleMessage(viewer, new BinaryMessage(SYNC_UPDATE_FRAME));

        assertThat(engineClient.latest().toEngine().frames).isEmpty();
        assertThat(counter(SessionMetrics.WRITE_DROPPED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sync 프레임은 awareness 분기에 삼켜지지 않고 엔진으로 간다(회귀)")
    void syncFrame_stillReachesEngine() throws Exception {
        RecordingWsSession editor = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession peer = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(editor, peer);

        handler.handleMessage(editor, new BinaryMessage(SYNC_UPDATE_FRAME));

        ClientFrame forwarded = engineClient.opened.getFirst().toEngine().frames.getFirst();
        assertThat(forwarded.getUpdate().toByteArray()).containsExactly(0x55, 0x66);
        // 그리고 sync는 룸 릴레이를 타지 않는다 — fan-out은 엔진 broadcast의 몫이다.
        assertThat(peer.sent).isEmpty();
    }

    @Test
    @DisplayName("종료된 세션은 릴레이 대상이 아니다 — 룸 인덱스에서도 즉시 빠진다")
    void closedSession_isNotARelayTarget() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession gone = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, gone);
        handler.afterConnectionClosed(gone, CloseStatus.NORMAL);

        handler.handleMessage(a, new BinaryMessage(AWARENESS_FRAME));

        assertThat(gone.sent).isEmpty();
    }

    @Test
    @DisplayName("종료된 세션이 보낸 awareness는 릴레이되지 않는다")
    void awarenessFromClosedSession_isIgnored() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b);
        handler.afterConnectionClosed(a, CloseStatus.NORMAL);

        handler.handleMessage(a, new BinaryMessage(AWARENESS_FRAME));

        assertThat(b.sent).isEmpty();
    }

    @Test
    @DisplayName("상한 초과 awareness는 그 프레임만 버린다 — 룸 fan-out 증폭 차단, 세션은 유지")
    void oversizedAwareness_isDroppedWithoutClosingSession() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b);

        handler.handleMessage(a, new BinaryMessage(oversizedAwarenessFrame()));

        assertThat(b.sent).isEmpty();
        assertThat(a.closeStatus).isNull();
        assertThat(b.closeStatus).isNull();
    }

    @Test
    @DisplayName("상한 이하 awareness는 통과한다 — 상한이 정상 트래픽을 자르지 않는다(경계)")
    void awarenessAtLimit_isRelayed() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b);

        handler.handleMessage(a, new BinaryMessage(
                awarenessFrame(new byte[DocWebSocketHandler.MAX_AWARENESS_PAYLOAD_BYTES])));

        assertThat(b.sent).hasSize(1);
    }

    @Test
    @DisplayName("프레이밍이 깨진 awareness는 그 프레임만 무시하고 세션을 유지한다")
    void malformedAwareness_dropsFrameOnly() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b);

        // 길이 5를 선언했지만 1바이트만 있다
        handler.handleMessage(a, new BinaryMessage(new byte[]{0x01, 0x05, 0x42}));

        assertThat(b.sent).isEmpty();
        assertThat(a.closeStatus).isNull();
    }

    @Test
    @DisplayName("auth(2)·미인식 top-level 타입은 릴레이도 forward도 되지 않는다")
    void authAndUnknownTypes_goNowhere() throws Exception {
        RecordingWsSession a = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession b = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(a, b);

        handler.handleMessage(a, new BinaryMessage(new byte[]{0x02, 0x00}));
        handler.handleMessage(a, new BinaryMessage(new byte[]{0x63, 0x00}));

        assertThat(b.sent).isEmpty();
        assertThat(engineClient.opened.getFirst().toEngine().frames).isEmpty();
    }

    // ─── join 시 queryAwareness 발신 (§1.3) ───

    @Test
    @DisplayName("신규 접속 시 게이트웨이가 기존 peer에게 queryAwareness를 발신한다(자신에게는 보내지 않음)")
    void join_sendsQueryAwarenessToExistingPeers() {
        // Given: 먼저 붙어 있는 두 세션
        RecordingWsSession first = openSession(ROOM_A, SessionRole.EDITOR);
        RecordingWsSession second = openSession(ROOM_A, SessionRole.EDITOR);
        // first는 second의 join으로 이미 1건 받았다 — 여기서 비우고 세 번째 join만 관찰한다.
        drainQueryAwareness(first, second);

        // When: 세 번째 세션이 붙는다
        RecordingWsSession joiner = openSession(ROOM_A, SessionRole.EDITOR);

        // Then: 기존 peer 전원이 queryAwareness를 받고, 신규 세션은 받지 않는다.
        // (신규 세션은 아직 공유할 상태가 없다 — y-websocket이 onopen에서 자기 상태를 스스로 보낸다)
        assertThat(first.sent).containsExactly(QUERY_AWARENESS_FRAME);
        assertThat(second.sent).containsExactly(QUERY_AWARENESS_FRAME);
        assertThat(joiner.sent).isEmpty();
    }

    @Test
    @DisplayName("룸의 첫 세션 join은 아무에게도 발신하지 않는다")
    void join_firstSessionInRoom_sendsNothing() {
        RecordingWsSession first = openSession(ROOM_A, SessionRole.EDITOR);

        assertThat(first.sent).isEmpty();
    }

    @Test
    @DisplayName("join 시 queryAwareness는 룸을 넘지 않는다")
    void join_queryAwarenessDoesNotCrossRooms() {
        RecordingWsSession other = openSession(ROOM_B, SessionRole.EDITOR);
        drainQueryAwareness(other);

        openSession(ROOM_A, SessionRole.EDITOR);

        assertThat(other.sent).isEmpty();
    }

    @Test
    @DisplayName("viewer가 붙어도 기존 peer에게 queryAwareness가 나간다 — viewer도 남의 커서를 본다")
    void join_asViewer_stillQueriesPeers() {
        RecordingWsSession editor = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(editor);

        openSession(ROOM_A, SessionRole.VIEWER);

        assertThat(editor.sent).containsExactly(QUERY_AWARENESS_FRAME);
    }

    @Test
    @DisplayName("엔진 연결 실패로 열리지 못한 세션은 기존 peer에게 질의하지 않는다")
    void join_whenEngineUnavailable_sendsNoQuery() {
        RecordingWsSession existing = openSession(ROOM_A, SessionRole.EDITOR);
        drainQueryAwareness(existing);
        engineClient.failOnOpen = true;

        RecordingWsSession failing = new RecordingWsSession();
        failing.getAttributes().put(HandshakeAttributes.ROOM_ATTRIBUTE, ROOM_A);
        failing.getAttributes().put(SessionRole.ATTRIBUTE, SessionRole.EDITOR);
        handler.afterConnectionEstablished(failing);

        // 등록 자체가 없으므로 질의도 없다 — 실패한 세션만 닫힌다.
        assertThat(existing.sent).isEmpty();
        assertThat(failing.closeStatus).isNotNull();
    }

    // ─── 헬퍼 ───

    private RecordingWsSession openSession(RoomId room, SessionRole role) {
        RecordingWsSession session = new RecordingWsSession();
        session.getAttributes().put(HandshakeAttributes.ROOM_ATTRIBUTE, room);
        session.getAttributes().put(SessionRole.ATTRIBUTE, role);
        handler.afterConnectionEstablished(session);
        return session;
    }

    /// join 발신으로 쌓인 queryAwareness 프레임을 비운다 — 릴레이 테스트가 그 노이즈를 보지 않도록.
    private static void drainQueryAwareness(RecordingWsSession... sessions) {
        for (RecordingWsSession session : sessions) {
            session.sent.clear();
        }
    }

    private static byte[] oversizedAwarenessFrame() {
        return awarenessFrame(new byte[DocWebSocketHandler.MAX_AWARENESS_PAYLOAD_BYTES + 1]);
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
}

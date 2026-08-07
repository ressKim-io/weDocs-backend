package io.wedocs.gateway.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// raw WS 클라이언트 2개 ↔ gateway 종단 awareness 검증. (M3 plan §1.3 · §검증 Phase 1)
///
/// 단위 테스트(`DocWebSocketAwarenessTest`)가 라우팅 결정을 검증하고, 여기서는 그 결정이 **실제 WS
/// 전송을 타고** 상대 브라우저에 도착하는지를 본다. 특히 join 시 queryAwareness 발신 → peer 응답 →
/// 신규 세션 도달의 **전체 고리**가 이 클래스에서만 관측된다 — 그 고리가 끊기면 신규 접속자는
/// 기존 peer가 움직일 때까지(최악 ~15초) 그를 보지 못한다.
class DocWebSocketAwarenessIntegrationTest extends AbstractWsIntegrationTest {

    private static final String ROOM_RELAY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String ROOM_QUERY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String ROOM_ISOLATED = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    /// [messageAwareness=1, varBuffer({0xAA,0xBB})] — 게이트웨이가 해석하지 않는 불투명 페이로드
    private static final byte[] AWARENESS_FRAME = {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
    /// [messageQueryAwareness=3] — 페이로드 없음
    private static final byte[] QUERY_AWARENESS_FRAME = {0x03};

    @Test
    @DisplayName("awareness가 같은 룸의 다른 클라이언트에 릴레이되고, 발신자에게도 엔진에도 가지 않는다")
    void awareness_relayedToPeer_notEchoedAndNotSentToEngine() throws Exception {
        // Given: 같은 룸 2클라 + 두 gRPC 스트림 등록 완료
        CollectingHandler clientA = new CollectingHandler();
        CollectingHandler clientB = new CollectingHandler();
        WebSocketSession sessionA = connect(clientA, ROOM_RELAY);
        connect(clientB, ROOM_RELAY);
        engine().awaitObservers(2, TIMEOUT_MS);
        // B의 join으로 A가 받은 queryAwareness를 먼저 소비(§1.3 발신)
        assertThat(clientA.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isEqualTo(QUERY_AWARENESS_FRAME);

        // When: A가 awareness 송신
        sessionA.sendMessage(new BinaryMessage(AWARENESS_FRAME));

        // Then: B가 페이로드 그대로 받는다
        assertThat(clientB.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isEqualTo(AWARENESS_FRAME);
        // self-echo 없음(§1.4) — 부재 증명
        assertThat(clientA.received.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
        // 엔진을 통과하지 않는다(판단 1) — awareness가 문서 sync와 실패 도메인을 공유하지 않는 근거
        assertThat(engine().receivedFrames.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("join 시 기존 peer에 queryAwareness가 나가고, 그 응답이 신규 세션에 도달한다(§1.3 전체 고리)")
    void join_queryAwareness_roundTripsToNewSession() throws Exception {
        // Given: 먼저 붙어 **가만히 있는** peer. 이 상태가 §1.3이 없애려던 문제다 —
        // 릴레이만 있으면 이 peer는 다음에 움직일 때까지 신규 접속자에게 보이지 않는다.
        CollectingHandler existing = new CollectingHandler();
        WebSocketSession existingSession = connect(existing, ROOM_QUERY);
        engine().awaitObservers(1, TIMEOUT_MS);

        // When: 신규 세션이 붙는다
        CollectingHandler joiner = new CollectingHandler();
        connect(joiner, ROOM_QUERY);

        // Then: 게이트웨이가 **발신자**가 되어 기존 peer에게 queryAwareness(3)를 보낸다.
        // (y-websocket은 이 프레임을 WS로 보내지 않으므로 릴레이로는 성립하지 않는다 — 실측)
        assertThat(existing.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isEqualTo(QUERY_AWARENESS_FRAME);
        // 신규 세션은 질의를 받지 않는다(공유할 상태가 없다)
        assertThat(joiner.received.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();

        // And: peer가 전체 awareness 상태로 응답하면(실브라우저의 messageHandlers[3] 동작),
        // 게이트웨이가 평소 릴레이 경로로 신규 세션에 전달한다 → 신규 접속자가 즉시 커서를 본다.
        existingSession.sendMessage(new BinaryMessage(AWARENESS_FRAME));
        assertThat(joiner.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isEqualTo(AWARENESS_FRAME);
    }

    @Test
    @DisplayName("룸의 첫 접속자는 queryAwareness를 유발하지 않고 아무도 프레임을 받지 않는다")
    void firstJoinInRoom_producesNoFrames() throws Exception {
        CollectingHandler only = new CollectingHandler();
        connect(only, ROOM_ISOLATED);
        engine().awaitObservers(1, TIMEOUT_MS);

        assertThat(only.received.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
    }
}

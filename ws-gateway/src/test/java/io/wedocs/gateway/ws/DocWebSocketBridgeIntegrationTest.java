package io.wedocs.gateway.ws;

import io.wedocs.gateway.auth.AuthSubprotocol;
import io.wedocs.proto.crdt.ClientFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// raw WS 클라이언트 ↔ gateway ↔ fake gRPC 엔진 종단 검증.
/// 검증: (1) doc-id 메타데이터 전파 + 인바운드 SyncStep1 forward, (2) ServerFrame{update} → WS Update(2) fan-out(2클라).
/// 실제 Rust 엔진 수렴은 로컬 스모크 + Phase 3 E2E가 담당(여기선 와이어↔gRPC 브리지 배선만 결정적으로 검증).
/// 인가 동작은 [DocWebSocketAuthzIntegrationTest] 담당 — 여기선 기본값(editor 허용)에 기댄다.
class DocWebSocketBridgeIntegrationTest extends AbstractWsIntegrationTest {

    private static final String ROOM_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROOM_B = "22222222-2222-4222-8222-222222222222";
    private static final String ROOM_C = "33333333-3333-4333-8333-333333333333";

    @Test
    @DisplayName("WS 접속 시 doc-id 메타데이터가 엔진에 전파되고 SyncStep1이 ClientFrame{state_vector}로 forward된다")
    void inbound_syncStep1_propagatesMetadataAndForwardsFrame() throws Exception {
        // Given: /ws/doc/{room} 접속
        WebSocketSession session = connect(new CollectingHandler(), ROOM_A);

        // When: 브라우저가 SyncStep1(SV={1,2,3}) 송신
        session.sendMessage(new BinaryMessage(new byte[]{0x00, 0x00, 0x03, 1, 2, 3}));

        // Then: 엔진이 메타데이터 doc-id를 보았고, ClientFrame{doc_id, state_vector={1,2,3}} 수신
        assertThat(engine().observedDocIds.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(ROOM_A);
        ClientFrame frame = engine().receivedFrames.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame.getDocId()).isEqualTo(ROOM_A);
        assertThat(frame.getStateVector().toByteArray()).containsExactly(1, 2, 3);
        assertThat(frame.getUpdate().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("한 클라의 update가 엔진 broadcast로 두 클라 모두에게 WS Update(2)로 fan-out된다")
    void update_fansOutToAllClientsAsUpdate2() throws Exception {
        // Given: 같은 room에 두 클라 접속 + 두 gRPC 스트림 등록 완료까지 대기
        CollectingHandler clientA = new CollectingHandler();
        CollectingHandler clientB = new CollectingHandler();
        WebSocketSession sessionA = connect(clientA, ROOM_B);
        connect(clientB, ROOM_B);
        engine().awaitObservers(2, TIMEOUT_MS);

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
        WebSocketSession session = connect(new CollectingHandler(), ROOM_C);
        engine().awaitObservers(1, TIMEOUT_MS);

        // When: 클라가 연결을 닫음
        session.close();

        // Then: 게이트웨이가 엔진 요청 스트림을 완료시켜 누수가 없다
        assertThat(engine().completedStreams.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
    }

    @Test
    @DisplayName("형식 위반 room(불허 문자)은 핸드셰이크 단계에서 거절된다(업그레이드 전, 엔진 미관측)")
    void invalidRoom_rejectedAtHandshake() throws Exception {
        // Given/When: 불허 문자(.)가 포함된 room으로 접속 시도
        // Then: 인터셉터가 업그레이드 전 400으로 거절 → 핸드셰이크 실패, 엔진은 doc-id를 관측하지 못한다.
        assertThatThrownBy(() -> connect(new CollectingHandler(), "bad.room"))
                .isInstanceOf(ExecutionException.class);
        assertThat(engine().observedDocIds.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("화이트리스트에 없는 Origin의 핸드셰이크는 거부된다(네이티브 WS 유일 방어선)")
    void disallowedOrigin_rejectedAtHandshake() throws Exception {
        // Given: 허용되지 않은 Origin + 유효 room + 유효 토큰(인증이 통과해도 Origin으로 걸리는지 격리 검증)
        double okBefore = handshakeCount("ok");
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("https://evil.example");
        headers.setSecWebSocketProtocol(List.of(AuthSubprotocol.SENTINEL, validToken()));
        String url = "ws://localhost:" + port + "/ws/doc/" + ROOM_A;

        // When/Then: 인증이 유효해도 Origin 불일치로 핸드셰이크 실패 → 엔진 미관측.
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(), headers, URI.create(url))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(engine().observedDocIds.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
        // 최종 응답이 403인 핸드셰이크는 result=ok로 세지 않는다 — 앱 신호와 상태코드 정합(ADR-0021, code-review H-1).
        assertThat(handshakeCount("ok")).isEqualTo(okBefore);
    }

    @Test
    @DisplayName("토큰 없는 핸드셰이크는 인증 실패로 거절된다(업그레이드 전, 엔진 미관측)")
    void noToken_rejectedAtHandshake() throws Exception {
        // Given: 인증 서브프로토콜(토큰) 없이 유효 room으로 접속 시도
        String url = "ws://localhost:" + port + "/ws/doc/" + ROOM_A;

        // When/Then: auth 인터셉터가 업그레이드 전 401로 거절 → 핸드셰이크 실패, 엔진은 doc-id를 관측 못 함.
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new CollectingHandler(), url)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(engine().observedDocIds.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("핸드셰이크 응답은 SENTINEL만 협상하고 토큰은 반향하지 않는다(토큰 유출 방지)")
    void handshake_echoesOnlySentinelSubprotocol() throws Exception {
        // Given/When: [SENTINEL, <jwt>]로 접속 성공
        double okBefore = handshakeCount("ok");
        WebSocketSession session = connect(new CollectingHandler(), ROOM_A);

        // Then: 서버가 협상한 서브프로토콜은 SENTINEL뿐 — 토큰은 응답 헤더로 새지 않는다(단 하나만 협상 가능).
        assertThat(session.getAcceptedProtocol()).isEqualTo(AuthSubprotocol.SENTINEL);
        // 성공 핸드셰이크는 afterHandshake(101 이후 실행)에서 result=ok로 집계된다 — H-1 수정의 정상 경로 실증(await).
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(handshakeCount("ok")).isEqualTo(okBefore + 1));
    }
}

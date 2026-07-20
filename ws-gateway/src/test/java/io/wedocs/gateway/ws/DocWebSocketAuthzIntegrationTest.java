package io.wedocs.gateway.ws;

import io.grpc.Status;
import io.wedocs.proto.common.Role;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.doc.CheckPermissionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// WS 핸드셰이크 인가 + viewer 다층 방어 1차 종단 검증 (ADR-0014 · ADR-0021, Phase 2a-2).
/// 인가 판정은 fake doc-service와의 **실제 gRPC 왕복**을 거친다 — 배선(채널·deadline·fail-closed)이
/// 규칙과 함께 동작하는지는 in-process 스텁으로는 확인되지 않는다(규칙 자체는 AuthzHandshakeInterceptorTest).
class DocWebSocketAuthzIntegrationTest extends AbstractWsIntegrationTest {

    private static final String ROOM = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    /// y-protocols 와이어 프레임: [messageSync=0][syncType][len][payload...]
    private static final byte[] SYNC_STEP1 = {0x00, 0x00, 0x03, 1, 2, 3};
    private static final byte[] SYNC_UPDATE = {0x00, 0x02, 0x02, 0x55, 0x66};

    @Test
    @DisplayName("editor 세션은 요청한 doc_id·user_id로 인가를 받고 role=editor를 엔진에 전달한다")
    void editor_passesRoleMetadataToEngine() throws Exception {
        // Given: doc-service가 editor 허용
        docService().behave(FakeDocService.Behavior.allow(Role.ROLE_EDITOR));

        // When: 접속
        connect(new CollectingHandler(), ROOM);

        // Then: 인가 요청이 게이트웨이가 검증한 식별자 그대로 전달되고, 엔진은 role=editor를 관측한다.
        CheckPermissionRequest request = docService().requests.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getDocId()).isEqualTo(ROOM);
        assertThat(request.getUserId()).isEqualTo(USER_ID);
        assertThat(engine().observedRoles.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo("editor");
    }

    @Test
    @DisplayName("editor 세션의 update는 엔진으로 전달된다(인가 도입이 정상 편집을 막지 않는다)")
    void editor_updateReachesEngine() throws Exception {
        docService().behave(FakeDocService.Behavior.allow(Role.ROLE_EDITOR));
        WebSocketSession session = connect(new CollectingHandler(), ROOM);
        engine().awaitObservers(1, TIMEOUT_MS);

        session.sendMessage(new BinaryMessage(SYNC_UPDATE));

        ClientFrame frame = engine().receivedFrames.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame.getUpdate().toByteArray()).containsExactly(0x55, 0x66);
    }

    @Test
    @DisplayName("viewer 세션의 update는 엔진에 도달하지 않고 드롭이 계측된다(D-5 1차 방어)")
    void viewer_updateIsDroppedBeforeEngine() throws Exception {
        // Given: viewer로 접속 + 스트림 등록 완료
        docService().behave(FakeDocService.Behavior.allow(Role.ROLE_VIEWER));
        WebSocketSession session = connect(new CollectingHandler(), ROOM);
        engine().awaitObservers(1, TIMEOUT_MS);
        assertThat(engine().observedRoles.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo("viewer");
        double droppedBefore = writeDroppedCount();

        // When: viewer가 쓰기(update) 프레임 송신
        session.sendMessage(new BinaryMessage(SYNC_UPDATE));

        // Then: 엔진은 그 프레임을 받지 못한다. 부재만으로는 "아무 일도 안 일어남"과 구분되지 않으므로,
        // 드롭 카운터 증가를 함께 확인해 차단 코드가 실제로 실행됐음을 증명한다.
        assertThat(engine().receivedFrames.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
        assertThat(writeDroppedCount()).isEqualTo(droppedBefore + 1);
    }

    @Test
    @DisplayName("viewer 세션의 SyncStep1(읽기)은 통과한다 — 막으면 읽기 자체가 불가능해진다")
    void viewer_syncStep1StillReachesEngine() throws Exception {
        docService().behave(FakeDocService.Behavior.allow(Role.ROLE_VIEWER));
        WebSocketSession session = connect(new CollectingHandler(), ROOM);
        engine().awaitObservers(1, TIMEOUT_MS);

        session.sendMessage(new BinaryMessage(SYNC_STEP1));

        ClientFrame frame = engine().receivedFrames.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame.getStateVector().toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("권한 없는 사용자는 업그레이드 전 거절되고 엔진 스트림이 열리지 않는다")
    void noPermission_rejectedBeforeUpgrade() throws Exception {
        docService().behave(FakeDocService.Behavior.deny());
        double deniedBefore = handshakeCount("authz_denied");

        assertThatThrownBy(() -> connect(new CollectingHandler(), ROOM)).isInstanceOf(ExecutionException.class);

        assertThat(engine().observedDocIds.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
        assertThat(handshakeCount("authz_denied")).isEqualTo(deniedBefore + 1);
    }

    @Test
    @DisplayName("doc-service 장애 시 연결을 거절하고(fail-closed) 정상 거부와 구분되는 장애 신호를 남긴다")
    void docServiceDown_failsClosedWithDistinctSignal() throws Exception {
        docService().behave(FakeDocService.Behavior.error(Status.UNAVAILABLE));
        double backendErrorBefore = handshakeCount("backend_error");
        double deniedBefore = handshakeCount("authz_denied");

        assertThatThrownBy(() -> connect(new CollectingHandler(), ROOM)).isInstanceOf(ExecutionException.class);

        assertThat(engine().observedDocIds.poll(ABSENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNull();
        assertThat(handshakeCount("backend_error")).isEqualTo(backendErrorBefore + 1);
        assertThat(handshakeCount("authz_denied")).isEqualTo(deniedBefore); // 장애가 정상 거부에 묻히지 않는다
        assertThat(meterRegistry.find("authz.backend.error").counter().count()).isPositive();
    }

    @Test
    @DisplayName("doc-service 응답 지연이 deadline을 넘으면 핸드셰이크가 매달리지 않고 거절된다")
    void slowDocService_hitsDeadlineAndRejects() {
        // Given: deadline(300ms)보다 느린 응답
        docService().behave(FakeDocService.Behavior.slow(3_000));
        double backendErrorBefore = handshakeCount("backend_error");

        // When/Then: 지연에 끌려가지 않고 거절 — deadline이 없으면 이 연결은 3초를 붙잡는다.
        assertThatThrownBy(() -> connect(new CollectingHandler(), ROOM)).isInstanceOf(ExecutionException.class);
        assertThat(handshakeCount("backend_error")).isEqualTo(backendErrorBefore + 1);
    }

    @Test
    @DisplayName("UUID가 아닌 room은 인가 백엔드를 호출하지 않고 거절된다(무의미한 왕복 제거)")
    void nonUuidRoom_rejectedWithoutCallingDocService() {
        assertThatThrownBy(() -> connect(new CollectingHandler(), "demo")).isInstanceOf(ExecutionException.class);

        assertThat(docService().requests).isEmpty();
    }

    @Test
    @DisplayName("인가 거절(403) 핸드셰이크는 result=ok로 집계되지 않는다(H-1 회귀 가드)")
    void authzDenied_doesNotCountAsHandshakeOk() {
        docService().behave(FakeDocService.Behavior.deny());
        double okBefore = handshakeCount("ok");

        assertThatThrownBy(() -> connect(new CollectingHandler(), ROOM)).isInstanceOf(ExecutionException.class);

        // 인증은 통과했으므로 auth 인터셉터의 afterHandshake가 실행되지만, 최종 상태코드가 403이라 ok가 아니다.
        assertThat(handshakeCount("ok")).isEqualTo(okBefore);
    }

    private double writeDroppedCount() {
        var counter = meterRegistry.find("ws.write.dropped").tag("reason", "viewer").counter();
        return counter == null ? 0.0 : counter.count();
    }
}

package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// y-websocket/y-protocols 와이어 ↔ gRPC 프레임 번역 검증.
/// 와이어 포맷(SSOT §A): top-level `varUint(type)`, sync 서브 `varUint(subtype)·varBuffer(payload)`.
class YProtocolCodecTest {

    private static final String DOC_ID = "demo";

    private final YProtocolCodec codec = new YProtocolCodec();

    @Test
    @DisplayName("SyncStep1(0) 디코드 → ClientFrame.state_vector, doc_id 채움, update 비움")
    void decodeInbound_syncStep1_mapsToStateVector() {
        // Given: [messageSync=0, SyncStep1=0, varBuffer({1,2,3})]
        byte[] ws = {0x00, 0x00, 0x03, 1, 2, 3};

        // When
        Optional<ClientFrame> frame = codec.decodeInbound(ws, DOC_ID);

        // Then
        assertThat(frame).isPresent();
        assertThat(frame.get().getDocId()).isEqualTo(DOC_ID);
        assertThat(frame.get().getStateVector().toByteArray()).containsExactly(1, 2, 3);
        assertThat(frame.get().getUpdate().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("SyncStep2(1) 디코드 → ClientFrame.update")
    void decodeInbound_syncStep2_mapsToUpdate() {
        // Given: [messageSync=0, SyncStep2=1, varBuffer({9,8,7,6})]
        byte[] ws = {0x00, 0x01, 0x04, 9, 8, 7, 6};

        // When
        Optional<ClientFrame> frame = codec.decodeInbound(ws, DOC_ID);

        // Then
        assertThat(frame).isPresent();
        assertThat(frame.get().getUpdate().toByteArray()).containsExactly(9, 8, 7, 6);
        assertThat(frame.get().getStateVector().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Update(2) 디코드 → ClientFrame.update")
    void decodeInbound_update_mapsToUpdate() {
        // Given: [messageSync=0, Update=2, varBuffer({42})]
        byte[] ws = {0x00, 0x02, 0x01, 42};

        // When
        Optional<ClientFrame> frame = codec.decodeInbound(ws, DOC_ID);

        // Then
        assertThat(frame).isPresent();
        assertThat(frame.get().getUpdate().toByteArray()).containsExactly(42);
    }

    @Test
    @DisplayName("非sync top-level 타입은 엔진으로 가지 않는다(empty)")
    void decodeInbound_nonSyncMessages_doNotReachEngine() {
        // Given: awareness(1)·auth(2)·queryAwareness(3)·미인식(99) — 페이로드 형태 무관, 첫 varUint만 본다.
        //
        // ⚠️ M3 Phase 1에서 이 테스트의 **의미**가 바뀌었다(계약 전환 지점).
        // 이전: 네 타입 모두 "버려진다".
        // 지금: awareness(1)는 버려지지 않는다 — 엔진을 통과하지 않고 게이트웨이가 룸에 릴레이한다
        //       (decodeAwareness가 그 경로다). 여기서 empty인 것은 "엔진으로 갈 프레임이 아니다"라는 뜻.
        // auth(2)·queryAwareness(3)·미인식은 여전히 어디로도 가지 않는다.
        assertThat(codec.decodeInbound(new byte[]{0x01, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x02, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x03, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x63, 0x00}, DOC_ID)).isEmpty();
    }

    // ─── awareness (M3 Phase 1) ───

    @Test
    @DisplayName("awareness(1) 디코드 → 페이로드 바이트를 그대로 꺼낸다(해석하지 않는다)")
    void decodeAwareness_extractsPayloadVerbatim() {
        // Given: [messageAwareness=1, varBuffer({0xAA,0xBB,0xCC})]
        byte[] ws = {0x01, 0x03, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC};

        // When
        Optional<byte[]> payload = codec.decodeAwareness(ws);

        // Then: 안의 clientId·상태는 불투명 바이트로 남는다(§1.2 무해석 불변식)
        assertThat(payload).isPresent();
        assertThat(payload.get()).containsExactly(0xAA, 0xBB, 0xCC);
    }

    @Test
    @DisplayName("sync·auth·queryAwareness·미인식은 awareness 경로에서 empty — sync 경로로 넘어간다")
    void decodeAwareness_nonAwarenessMessages_areEmpty() {
        assertThat(codec.decodeAwareness(new byte[]{0x00, 0x02, 0x01, 42})).isEmpty();
        assertThat(codec.decodeAwareness(new byte[]{0x02, 0x00})).isEmpty();
        assertThat(codec.decodeAwareness(new byte[]{0x03})).isEmpty();
        assertThat(codec.decodeAwareness(new byte[]{0x63, 0x00})).isEmpty();
    }

    @Test
    @DisplayName("프레이밍이 깨진 awareness(선언 길이 > 실제 바이트)는 예외 — 룸 전체로 증폭시키지 않는다")
    void decodeAwareness_truncatedPayload_throws() {
        // Given: 길이 5를 선언했지만 1바이트만 있다.
        // 원본 바이트를 통째로 릴레이하는 설계라면 이 프레임이 N명의 파서를 동시에 넘어뜨린다.
        byte[] ws = {0x01, 0x05, 0x42};

        assertThatThrownBy(() -> codec.decodeAwareness(ws))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 프레임은 awareness 경로에서도 예외(조기 종료) — 호출부가 프레임 단위로 흡수한다")
    void decodeAwareness_emptyFrame_throws() {
        assertThatThrownBy(() -> codec.decodeAwareness(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("awareness 인코드 → [1, len, payload]")
    void encodeAwareness_framesWithTypeAndLength() {
        byte[] ws = codec.encodeAwareness(new byte[]{0x11, 0x22});

        assertThat(ws).containsExactly(0x01, 0x02, 0x11, 0x22);
    }

    @Test
    @DisplayName("awareness 라운드트립: 인코드한 프레임을 디코드하면 같은 페이로드")
    void roundTrip_awareness() {
        byte[] payload = {7, 6, 5, 4, 3, 2, 1};

        byte[] ws = codec.encodeAwareness(payload);

        assertThat(codec.decodeAwareness(ws)).contains(payload);
    }

    @Test
    @DisplayName("queryAwareness 인코드 → 타입 3 단일 바이트, 페이로드 없음")
    void encodeQueryAwareness_isTypeOnly() {
        // y-websocket이 bc.publish로 보내는 것과 동일한 형태(type byte만) — 그 클라이언트의
        // messageHandlers[3]이 이 프레임을 받아 전체 awareness 상태로 응답한다.
        assertThat(codec.encodeQueryAwareness()).containsExactly(0x03);
    }

    @Test
    @DisplayName("queryAwareness 프레임은 호출마다 새 배열 — 공유 가변 배열을 노출하지 않는다")
    void encodeQueryAwareness_returnsFreshArray() {
        byte[] first = codec.encodeQueryAwareness();
        byte[] second = codec.encodeQueryAwareness();

        assertThat(first).isNotSameAs(second).isEqualTo(second);
    }

    @Test
    @DisplayName("미인식 sync 서브타입은 예외 없이 drop(empty)")
    void decodeInbound_unknownSyncSubtype_isDropped() {
        // Given: [messageSync=0, subtype=7(미정의), varBuffer({1})]
        byte[] ws = {0x00, 0x07, 0x01, 1};

        // When/Then: 에러 금지(§D-7) — 단순 무시
        assertThat(codec.decodeInbound(ws, DOC_ID)).isEmpty();
    }

    @Test
    @DisplayName("ServerFrame{state_vector} 인코드 → messageSync·SyncStep1·varBuffer")
    void encodeOutbound_stateVector_framesAsSyncStep1() {
        // Given
        ServerFrame frame = ServerFrame.newBuilder()
                .setStateVector(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();

        // When
        Optional<byte[]> ws = codec.encodeOutbound(frame);

        // Then: [0(messageSync), 0(SyncStep1), 3(len), 1,2,3]
        assertThat(ws).isPresent();
        assertThat(ws.get()).containsExactly(0x00, 0x00, 0x03, 1, 2, 3);
    }

    @Test
    @DisplayName("ServerFrame{update} 인코드 → messageSync·Update(2)·varBuffer (§D-4)")
    void encodeOutbound_update_framesAsUpdate2() {
        // Given
        ServerFrame frame = ServerFrame.newBuilder()
                .setUpdate(ByteString.copyFrom(new byte[]{9, 8, 7, 6}))
                .build();

        // When
        Optional<byte[]> ws = codec.encodeOutbound(frame);

        // Then: [0(messageSync), 2(Update), 4(len), 9,8,7,6] — SyncStep2가 아니라 전부 Update(2)
        assertThat(ws).isPresent();
        assertThat(ws.get()).containsExactly(0x00, 0x02, 0x04, 9, 8, 7, 6);
    }

    @Test
    @DisplayName("빈 ServerFrame 인코드 → 전송 없음(empty)")
    void encodeOutbound_empty_emitsNothing() {
        assertThat(codec.encodeOutbound(ServerFrame.getDefaultInstance())).isEmpty();
    }

    @Test
    @DisplayName("ServerFrame{state_vector+update 둘 다} 인코드 → state_vector 우선(SyncStep1), update 드롭")
    void encodeOutbound_bothFieldsSet_prioritisesStateVector() {
        // Given: 엔진 계약상 발생하지 않아야 하나 proto가 oneof가 아니라 가능 — 우선순위를 고정(무성 유실 방지)
        ServerFrame frame = ServerFrame.newBuilder()
                .setStateVector(ByteString.copyFrom(new byte[]{1, 2}))
                .setUpdate(ByteString.copyFrom(new byte[]{9, 8}))
                .build();

        // When
        Optional<byte[]> ws = codec.encodeOutbound(frame);

        // Then: [messageSync=0, SyncStep1=0, varBuffer({1,2})] — update({9,8})는 전송되지 않음
        assertThat(ws).isPresent();
        assertThat(ws.get()).containsExactly(0x00, 0x00, 0x02, 1, 2);
    }

    @Test
    @DisplayName("라운드트립: ServerFrame{update} 인코드 → 같은 바이트를 inbound 디코드하면 update 복원")
    void roundTrip_updateFrame() {
        // Given
        byte[] update = {5, 4, 3, 2, 1};
        ServerFrame outbound = ServerFrame.newBuilder()
                .setUpdate(ByteString.copyFrom(update))
                .build();

        // When: 엔진→WS 인코드 후, 그 바이트를 WS→엔진으로 다시 디코드
        byte[] ws = codec.encodeOutbound(outbound).orElseThrow();
        Optional<ClientFrame> inbound = codec.decodeInbound(ws, DOC_ID);

        // Then
        assertThat(inbound).isPresent();
        assertThat(inbound.get().getUpdate().toByteArray()).isEqualTo(update);
    }
}

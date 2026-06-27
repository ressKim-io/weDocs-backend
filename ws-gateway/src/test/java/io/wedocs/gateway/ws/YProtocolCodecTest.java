package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("awareness/auth/queryAwareness/미인식 top-level 타입은 drop(empty)")
    void decodeInbound_nonSyncMessages_areDropped() {
        // Given: awareness(1)·auth(2)·queryAwareness(3)·미인식(99) — 페이로드 형태 무관, 첫 varUint만 본다
        assertThat(codec.decodeInbound(new byte[]{0x01, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x02, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x03, 0x00}, DOC_ID)).isEmpty();
        assertThat(codec.decodeInbound(new byte[]{0x63, 0x00}, DOC_ID)).isEmpty();
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

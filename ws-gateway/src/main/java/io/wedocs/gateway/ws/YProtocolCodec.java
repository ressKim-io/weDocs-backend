package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

/// y-websocket/y-protocols 와이어 ↔ 엔진 gRPC 프레임 1:1 번역기 (I/O·gRPC 호출과 무관, 순수).
///
/// 게이트웨이는 Y.Doc이 없어 SyncStep1에 직접 답할 수 없으므로 sync 권위는 엔진에 있다.
/// 여기서는 프레이밍만 변환한다. (SSOT §C/§D)
final class YProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(YProtocolCodec.class);

    /// y-websocket top-level 메시지 타입
    static final int MESSAGE_SYNC = 0;
    static final int MESSAGE_AWARENESS = 1;
    static final int MESSAGE_AUTH = 2;
    static final int MESSAGE_QUERY_AWARENESS = 3;

    /// y-protocols sync 서브타입
    static final int SYNC_STEP1 = 0; // state vector
    static final int SYNC_STEP2 = 1; // diff update
    static final int SYNC_UPDATE = 2; // update

    /// 브라우저 → 엔진. messageSync만 ClientFrame으로 번역하고,
    /// awareness/auth/queryAwareness/미인식은 무시한다(empty, 에러 금지 — §D-7).
    Optional<ClientFrame> decodeInbound(byte[] wsMessage, String docId) {
        Lib0.Decoder decoder = new Lib0.Decoder(wsMessage);
        if (decoder.readVarUint() != MESSAGE_SYNC) {
            return Optional.empty();
        }
        long syncType = decoder.readVarUint();
        byte[] payload = decoder.readVarUint8Array();

        ClientFrame.Builder frame = ClientFrame.newBuilder().setDocId(docId);
        if (syncType == SYNC_STEP1) {
            return Optional.of(frame.setStateVector(ByteString.copyFrom(payload)).build());
        }
        if (syncType == SYNC_STEP2 || syncType == SYNC_UPDATE) {
            return Optional.of(frame.setUpdate(ByteString.copyFrom(payload)).build());
        }
        return Optional.empty();
    }

    /// 엔진 → 브라우저. state_vector는 SyncStep1, update는 전부 Update(2)로 프레이밍한다(§D-4).
    ///
    /// 엔진 계약(verified, crdt-engine service.rs): ServerFrame은 둘 중 정확히 하나만 채운다 —
    /// open=state_vector만(service.rs:66), inbound diff·broadcast=update만. proto가 oneof가 아니라
    /// 강제되진 않으므로, 만에 하나 둘 다 set이면 state_vector 우선 + 경고(무성 데이터 유실을 가시화).
    Optional<byte[]> encodeOutbound(ServerFrame frame) {
        boolean hasStateVector = !frame.getStateVector().isEmpty();
        boolean hasUpdate = !frame.getUpdate().isEmpty();
        if (hasStateVector && hasUpdate) {
            log.warn("ServerFrame에 state_vector와 update가 모두 설정됨 — state_vector만 전송(update 드롭). 엔진 계약 위반?");
        }
        if (hasStateVector) {
            return Optional.of(syncMessage(SYNC_STEP1, frame.getStateVector()));
        }
        if (hasUpdate) {
            return Optional.of(syncMessage(SYNC_UPDATE, frame.getUpdate()));
        }
        return Optional.empty();
    }

    private byte[] syncMessage(int syncType, ByteString payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, MESSAGE_SYNC);
        Lib0.writeVarUint(out, syncType);
        Lib0.writeVarUint8Array(out, payload.toByteArray());
        return out.toByteArray();
    }
}

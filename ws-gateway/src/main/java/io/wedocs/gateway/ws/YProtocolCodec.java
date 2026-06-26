package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

/// y-websocket/y-protocols 와이어 ↔ 엔진 gRPC 프레임 1:1 번역기 (I/O·gRPC 호출과 무관, 순수).
///
/// 게이트웨이는 Y.Doc이 없어 SyncStep1에 직접 답할 수 없으므로 sync 권위는 엔진에 있다.
/// 여기서는 프레이밍만 변환한다. (SSOT §C/§D)
final class YProtocolCodec {

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
    Optional<byte[]> encodeOutbound(ServerFrame frame) {
        if (!frame.getStateVector().isEmpty()) {
            return Optional.of(syncMessage(SYNC_STEP1, frame.getStateVector()));
        }
        if (!frame.getUpdate().isEmpty()) {
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

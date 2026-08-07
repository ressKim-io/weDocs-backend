package io.wedocs.gateway.ws;

import com.google.protobuf.ByteString;
import io.wedocs.gateway.common.logging.GatewayErrorType;
import io.wedocs.gateway.common.logging.GatewayLogEvent;
import io.wedocs.gateway.common.logging.LogEvents;
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

    /// 브라우저 → 엔진. messageSync만 ClientFrame으로 번역하고, 그 밖의 top-level 타입은
    /// 무시한다(empty, 에러 금지 — §D-7).
    ///
    /// ⚠️ empty가 곧 "버린다"는 뜻은 아니다. awareness(1)는 엔진을 통과하지 않고 게이트웨이가
    /// 룸에 릴레이하며(M3 §1.3), 그 분기는 호출부가 [#decodeAwareness]로 먼저 판정한다.
    /// 여기서 empty가 되는 것은 "**엔진으로 갈 프레임이 아니다**"라는 뜻이다.
    /// auth(2)·queryAwareness(3)·미인식은 여전히 어디로도 가지 않는다.
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
            LogEvents.event(log, GatewayLogEvent.FRAME_ANOMALY)
                    .errorType(GatewayErrorType.DUAL_FIELD)
                    .log();
        }
        if (hasStateVector) {
            return Optional.of(syncMessage(SYNC_STEP1, frame.getStateVector()));
        }
        if (hasUpdate) {
            return Optional.of(syncMessage(SYNC_UPDATE, frame.getUpdate()));
        }
        return Optional.empty();
    }

    /// 브라우저 → 게이트웨이. awareness(1) 프레임의 **페이로드 바이트를 그대로** 꺼낸다.
    /// 다른 top-level 타입이면 empty(호출부가 sync 경로로 넘긴다).
    ///
    /// 게이트웨이는 이 페이로드를 **해석하지 않는다** — 여기서 확인하는 것은 lib0 프레이밍 유효성뿐이고
    /// 안의 clientId·상태는 불투명 바이트로 릴레이된다(M3 §1.2의 무해석 불변식). 해석하기 시작하면
    /// ADR-0011 기각사유①을 무력화한 논거와 §1.5가 신원 stamp를 "파싱 비용"으로 기각한 논거가
    /// 동시에 무너진다.
    ///
    /// 원본 바이트를 통째로 되돌리지 않고 디코드 → 재인코드하는 이유: 프레이밍이 깨진 프레임
    /// (선언 길이 > 실제 바이트, 뒤에 붙은 잡음)을 룸 전체에 증폭시키지 않는다. N명에게 손상 프레임을
    /// 뿌리면 N개의 클라이언트 파서가 동시에 넘어진다.
    Optional<byte[]> decodeAwareness(byte[] wsMessage) {
        Lib0.Decoder decoder = new Lib0.Decoder(wsMessage);
        if (decoder.readVarUint() != MESSAGE_AWARENESS) {
            return Optional.empty();
        }
        return Optional.of(decoder.readVarUint8Array());
    }

    /// 게이트웨이 → 브라우저. awareness 페이로드를 y-websocket이 읽는 프레이밍으로 감싼다.
    byte[] encodeAwareness(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, MESSAGE_AWARENESS);
        Lib0.writeVarUint8Array(out, payload);
        return out.toByteArray();
    }

    /// 게이트웨이 → 브라우저. **페이로드가 없는** 타입 3 프레임 = "네가 아는 awareness 상태를 전부 보내라".
    ///
    /// 이것은 릴레이가 아니라 **게이트웨이 발신**이다. y-websocket 3.0.0은 queryAwareness를 WS로
    /// 보내지 않는다(`bc.publish` = 같은 브라우저 탭 간 BroadcastChannel 전용, 실측 2026-08-07
    /// `y-websocket.js:458~465`) — 그래서 릴레이로 구현하면 컴파일도 되고 테스트도 통과하는데
    /// **아무도 보내지 않으므로 dead code**다. 신규 접속자는 기존 peer가 다음에 움직일 때까지
    /// (하트비트 폴백 최악 ~15초) 그를 보지 못한다.
    ///
    /// 반대로 **받는** 쪽 핸들러는 이미 등록돼 있다(`messageHandlers[3]` → 전체 상태를 awareness(1)로
    /// 응답, `websocket.onmessage`가 그 응답을 WS로 되돌려보낸다). 그래서 클라이언트 변경 없이
    /// 게이트웨이가 질의자가 될 수 있다. 게이트웨이는 여기서도 상태를 보관하지 않는다 —
    /// 캐시가 아니라 **peer들에게 되묻는 것**이다.
    byte[] encodeQueryAwareness() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, MESSAGE_QUERY_AWARENESS);
        return out.toByteArray();
    }

    private byte[] syncMessage(int syncType, ByteString payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lib0.writeVarUint(out, MESSAGE_SYNC);
        Lib0.writeVarUint(out, syncType);
        Lib0.writeVarUint8Array(out, payload.toByteArray());
        return out.toByteArray();
    }
}

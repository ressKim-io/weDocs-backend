package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;
import io.wedocs.gateway.common.logging.GatewayErrorType;
import io.wedocs.gateway.common.logging.GatewayLogEvent;
import io.wedocs.gateway.common.logging.LogEvents;
import io.wedocs.gateway.common.logging.LogFields;
import io.wedocs.gateway.grpc.EngineClient;
import io.wedocs.gateway.handshake.HandshakeAttributes;
import io.wedocs.gateway.handshake.RoomId;
import io.wedocs.gateway.handshake.SessionRole;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// 브라우저 ↔ 엔진 브리지. WS 세션 하나당 엔진 `Sync` bidi 스트림 하나를 유지하며
/// y-websocket(바이너리) ↔ gRPC 프레임을 번역한다. (SSOT §C/§D)
///
/// ## 동시성 계약 (Concurrency Contract)
///
/// **WS send 직렬화(§D-6)**: 아웃바운드 WS send는 전부 `ConcurrentWebSocketSessionDecorator`를 통과한다.
///
/// 이전 계약은 "한 세션의 writer는 gRPC 응답 콜백 하나뿐"이라는 **단일 writer 불변식**이었고, 그래서
/// 데코레이터가 필요 없었다. M3 awareness fan-out이 그 불변식을 정면으로 깬다 — 같은 룸에 있는
/// **다른 세션의 인바운드 스레드**가 내 세션에 쓰기 때문에 writer가 N개가 된다. 동시 send는 컨테이너
/// 레벨에서 `IllegalStateException`(Tomcat: 진행 중인 write에 겹쳐 쓰기)으로 터지고, 그 실패는
/// 룸에 사람이 모일 때만 나타나므로 단위 테스트로 잡히지 않는다. 그래서 fan-out 코드보다 **먼저**
/// 감싼다(M3 plan §1.1) — 순서를 뒤집으면 그 사이 모든 fan-out 코드가 미검증 동시성 위에 쌓인다.
///
/// 초과 정책은 데코레이터 기본값 **TERMINATE**를 쓴다(`OverflowStrategy.DROP` 금지). 같은 세션이
/// 문서 sync 프레임도 나르므로, update를 조용히 버리면 그 클라이언트는 **수렴이 깨진 채 살아남는다**
/// (에러도 없고 재동기화 계기도 없다). 끊으면 재접속 → SyncStep1 → 전체 재동기화로 복구되므로
/// 드롭보다 종료가 정확성에 유리하다. awareness만 있는 세션이라면 DROP이 우아하겠지만, 프레임
/// 종류로 정책을 갈라놓을 수 있는 지점이 아니다(데코레이터는 세션 단위).
///
/// **요청 StreamObserver(toEngine) 직렬화**:
/// - `computeIfPresent`가 onNext와 onCompleted(bridges.remove)를 상호 배제한다
/// - Spring WS 스펙이 세션당 단일 스레드 메시지 처리를 보장 → onNext 동시 호출 없음
/// - ⚠️ 그래서 `computeIfPresent` 람다 안에서는 **다른 세션에 write하지 않는다** — CHM 키 락을 쥔 채
///   I/O를 하면 (a) VT pinning, (b) 같은 룸 세션끼리 지연 전파가 된다. fan-out은 원자 구간 밖에서 한다.
///
/// **응답 StreamObserver(toClient) 직렬화**:
/// - gRPC 런타임이 단일 observer에 대해 순차 호출을 보장(grpc-java 계약)
/// - {@link SerializingStreamObserver}로 추가 감싸 이중 완료 방지 및 구현 버그 방어(defense-in-depth)
///
/// **ConcurrentHashMap 원자성**:
/// - `bridges.remove()` — 여러 경로(afterConnectionClosed, handleTransportError, endSession)에서 호출되나
///   ConcurrentHashMap.remove()가 원자적으로 null 반환하여 중복 정리를 방지
@Component
public class DocWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocWebSocketHandler.class);

    /// 아웃바운드 단일 프레임의 실질 상한. 엔진 `ServerFrame`은 게이트웨이 gRPC 채널의 수신 상한
    /// (grpc-java 기본 4MiB)에 묶이고, 초기 sync-step2는 스냅샷 상한(doc-service `MAX_SNAPSHOT_BYTES`,
    /// ≈4MiB)까지 자란다 — 즉 정상 트래픽에도 4MiB급 프레임이 한 개 들어올 수 있다.
    private static final int MAX_OUTBOUND_FRAME_BYTES = 4 * 1024 * 1024;

    /// 데코레이터 송신 큐 상한. **최대 프레임 하나보다 반드시 커야 한다** — 낮추면 느린 클라이언트가
    /// *정상* 초기 동기화 중에 끊긴다(자기 발에 총 쏘는 상한). 최대 프레임 1개 + 후속 여유로 2배.
    ///
    /// ⚠️ 이 상한이 묶는 것은 **병리적 클라이언트 1개**의 힙 점유다. 세션 수 × 이 값의 총합은
    /// 여기서 제어되지 않는다 — 그건 Phase 4의 전역 세션 캡 몫이다(M3 plan §Phase 4).
    /// ⚠️ 임시값 — Phase 4에서 정량화하고 근거는 Phase 6 부하 측정이 붙인다.
    static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 2 * MAX_OUTBOUND_FRAME_BYTES;

    /// 데코레이터 송신 시간 상한 — 한 세션의 send가 이 시간 넘게 진행 중이면 세션을 끊는다.
    /// 느린 소비자가 룸 전체의 fan-out 스레드를 붙잡는 것을 막는 값이다(§1.1이 Phase 4 백프레셔로 예고).
    /// ⚠️ 임시값 — Phase 4에서 정량화.
    static final int SEND_TIME_LIMIT_MS = 10_000;

    private final EngineClient engineClient;
    private final SessionMetrics sessionMetrics;
    private final YProtocolCodec codec = new YProtocolCodec();
    private final Map<String, SessionBridge> bridges = new ConcurrentHashMap<>();
    /// `bridges`의 룸 역인덱스 — awareness fan-out 대상 조회용. 두 map의 정합성은 best-effort다.
    private final RoomRegistry rooms = new RoomRegistry();

    public DocWebSocketHandler(EngineClient engineClient, SessionMetrics sessionMetrics) {
        this.engineClient = engineClient;
        this.sessionMetrics = sessionMetrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // room·role은 핸드셰이크 인터셉터가 업그레이드 전에 검증해 attribute로 넣었다
        // (무효 room=400 / 무인증=401 / 무권한=403은 여기 도달 못 함).
        RoomId roomId = (RoomId) session.getAttributes().get(HandshakeAttributes.ROOM_ATTRIBUTE);
        Optional<SessionRole> role = SessionRole.from(session.getAttributes());
        if (roomId == null || role.isEmpty()) { // 방어: 인터셉터 미배선 시에만 발생 — 안전하게 닫는다.
            // 권한을 모른 채 스트림을 열면 viewer가 editor로 취급된다 — 열지 않는 쪽이 안전하다(fail-closed).
            closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("session identity not resolved"));
            return;
        }
        // 이 지점 이후 게이트웨이는 원본 세션에 직접 쓰지 않는다 — 모든 아웃바운드가 데코레이터를 통과한다(§D-6).
        WebSocketSession guarded = guardSends(session);
        // SerializingStreamObserver로 감싸 gRPC 응답 콜백의 onNext/onError/onCompleted 직렬화를 강제한다.
        // gRPC 런타임이 순차 호출을 보장하지만, 이중 완료 방지 및 구현 버그 방어를 위한 안전망(Req 11.2, 11.3).
        StreamObserver<ServerFrame> toClient =
                new SerializingStreamObserver<>(engineResponseObserver(guarded, roomId));
        // wire/log 경계마다 .value()로 언랩 — RoomId는 gateway 내부로 관통하고 String이 필요한 sink에서만 푼다.
        try {
            StreamObserver<ClientFrame> toEngine =
                    engineClient.openSync(roomId.value(), role.get().wireValue(), toClient);
            bridges.put(session.getId(), new SessionBridge(roomId, role.get(), guarded, toEngine));
            rooms.join(roomId, session.getId());
        } catch (RuntimeException e) {
            // openSync 실패 시 세션이 bridges에 등록되지 않아 afterConnectionClosed가 정리할 게 없다.
            // 열려 있는 WS 세션을 닫아 클라이언트에게 장애를 알린다.
            LogEvents.event(log, GatewayLogEvent.SESSION_OPEN_FAILED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .attr(LogFields.DOC_ID, roomId.value())
                    .errorType(GatewayErrorType.ENGINE_UNAVAILABLE)
                    .cause(e)
                    .log();
            closeQuietly(guarded, CloseStatus.SERVER_ERROR.withReason("engine unavailable"));
        }
    }

    /// 세션의 아웃바운드를 큐잉·직렬화하는 데코레이터로 감싼다 — 클래스 주석 §D-6의 처방.
    /// 상한 초과 시 데코레이터는 `SessionLimitExceededException`을 던지고 이후 send를 무음 처리하므로,
    /// 호출부(`sendBinary`)가 그 예외를 세션 종료로 번역한다.
    private static WebSocketSession guardSends(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT_BYTES);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] payload = toBytes(message.getPayload());
        // [Req 11.2 원자성 보장] get+onNext를 computeIfPresent로 원자화한다.
        // ConcurrentHashMap의 키 단위 락이 afterConnectionClosed/endSession의 remove와 상호 배제
        // → request StreamObserver에 onNext와 onCompleted가 동시 호출되지 않음(grpc-java 계약: 동시 호출 금지).
        // 이것이 non-atomic check-then-act를 방지하는 핵심 패턴이다: "브리지 존재 확인 → onNext 호출"을
        // 단일 원자 연산으로 합쳐 remove와의 경합을 제거한다. (§D-6 확장)
        bridges.computeIfPresent(session.getId(), (id, bridge) -> {
            try {
                codec.decodeInbound(payload, bridge.room().value())
                        .filter(frame -> isPermitted(frame, bridge, id))
                        .ifPresent(bridge.toEngine()::onNext);
            } catch (RuntimeException e) {
                // 손상 프레임 한 개로 세션을 죽이지 않는다 — 그 프레임만 무시(엔진의 손상 update 처리와 대칭).
                LogEvents.event(log, GatewayLogEvent.FRAME_DROPPED)
                        .attr(LogFields.SESSION_ID, id)
                        .attr(LogFields.DOC_ID, bridge.room().value())
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .cause(e)
                        .log();
            }
            return bridge;
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = detach(session.getId());
        if (bridge != null) {
            try {
                completeQuietly(bridge.toEngine()); // 클라가 떠났음을 엔진에 알림
            } finally {
                sessionMetrics.sessionClosed();
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LogEvents.event(log, GatewayLogEvent.TRANSPORT_FAILED)
                .attr(LogFields.SESSION_ID, session.getId())
                .errorType(GatewayErrorType.TRANSPORT_ERROR)
                .cause(exception)
                .log();
        // Spring 스펙상 afterConnectionClosed가 이어지지만, gRPC 엔진 스트림은 즉시 정리하여
        // 전송 에러 이후 afterConnectionClosed 호출까지의 시간 동안 엔진이 끊긴 세션에
        // 프레임을 보내는 것을 방지한다. bridges.remove()의 원자성이 이중 정리를 막는다.
        SessionBridge bridge = detach(session.getId());
        if (bridge != null) {
            try {
                completeQuietly(bridge.toEngine());
            } finally {
                sessionMetrics.sessionClosed();
            }
        }
    }

    /// viewer 세션이 보낸 쓰기 프레임을 엔진에 넘기지 않는다 — 인가 결정(ADR-0014)의 1차 집행이다.
    /// 최종 방어선은 엔진(2b): 게이트웨이를 우회한 직접 gRPC는 여기로 오지 않으므로 이 층만으로는 부족하다(D-5).
    ///
    /// "쓰기"의 판정은 update 페이로드 유무다. `ClientFrame`은 proto3 plain bytes라 presence가 없고
    /// (`hasUpdate()` 없음), 코덱이 SyncStep1은 state_vector에·Step2/Update는 update에 담는다. 빈 update는
    /// 문서를 바꿀 수 없는 no-op이므로 이 판정이 실제 쓰기를 놓치는 경우는 없다. SyncStep1은 통과시켜야 한다 —
    /// viewer도 초기 문서를 받으려면 state vector를 보내야 하기 때문(막으면 읽기 자체가 안 된다).
    private boolean isPermitted(ClientFrame frame, SessionBridge bridge, String sessionId) {
        if (bridge.role() != SessionRole.VIEWER || frame.getUpdate().isEmpty()) {
            return true;
        }
        sessionMetrics.writeDropped();
        LogEvents.event(log, GatewayLogEvent.WRITE_DROPPED)
                .attr(LogFields.SESSION_ID, sessionId)
                .attr(LogFields.DOC_ID, bridge.room().value())
                .errorType(GatewayErrorType.VIEWER_READ_ONLY)
                .log();
        return false;
    }

    /// 엔진 → 브라우저 방향. 이 콜백만이 WS의 유일한 writer다(§D-6).
    private StreamObserver<ServerFrame> engineResponseObserver(WebSocketSession session, RoomId room) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerFrame frame) {
                codec.encodeOutbound(frame).ifPresent(bytes -> sendBinary(session, bytes));
            }

            @Override
            public void onError(Throwable t) {
                LogEvents.event(log, GatewayLogEvent.ENGINE_STREAM_FAILED)
                        .attr(LogFields.SESSION_ID, session.getId())
                        .attr(LogFields.DOC_ID, room.value())
                        .errorType(GatewayErrorType.ENGINE_STREAM_ERROR)
                        .cause(t)
                        .log();
                endSession(session, CloseStatus.SERVER_ERROR);
            }

            @Override
            public void onCompleted() {
                endSession(session, CloseStatus.NORMAL);
            }
        };
    }

    private void sendBinary(WebSocketSession session, byte[] bytes) {
        try {
            session.sendMessage(new BinaryMessage(bytes));
        } catch (SessionLimitExceededException e) {
            // 데코레이터 상한 초과 = 느린 클라이언트. 전송 오류(SEND_FAILED)와 **구분해서** 남긴다 —
            // 원인이 네트워크가 아니라 우리가 정한 상한이고, 처방도 다르다(Phase 4의 수치 재조정).
            sessionMetrics.sendQueueExceeded();
            LogEvents.event(log, GatewayLogEvent.SEND_LIMIT_EXCEEDED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .errorType(GatewayErrorType.SEND_BUFFER_EXCEEDED)
                    .cause(e)
                    .log();
            endSession(session, CloseStatus.SERVER_ERROR);
        } catch (IOException e) {
            LogEvents.event(log, GatewayLogEvent.SEND_FAILED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .errorType(GatewayErrorType.SEND_FAILED)
                    .cause(e)
                    .log();
            endSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    /// 엔진이 스트림을 끝냈거나 WS send가 실패했을 때 WS를 닫는다. 브리지를 먼저 제거해 afterConnectionClosed와 중복 정리를 막되,
    /// 제거에 성공하면 엔진 요청 스트림도 완료시킨다 — sendBinary 실패 경로에서 요청 스트림이 누수되지 않도록(이 정리 누락 시 엔진이 계속 onNext→재실패 반복).
    private void endSession(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = detach(session.getId());
        try {
            if (bridge != null) {
                completeQuietly(bridge.toEngine());
            }
        } finally {
            // closeQuietly와 메트릭은 completeQuietly 예외(방어적)에 관계없이 실행되어야 한다.
            try {
                closeQuietly(session, status);
            } finally {
                if (bridge != null) {
                    sessionMetrics.sessionClosed();
                }
            }
        }
    }

    /// 세션을 `bridges`와 룸 인덱스에서 **함께** 떼어낸다.
    ///
    /// 정리 경로가 셋(afterConnectionClosed · handleTransportError · endSession)이므로 하나로 모은다 —
    /// 흩어놓으면 한 경로에서만 `leave`를 빠뜨리는 형태의 누수(떠난 세션이 fan-out 대상으로 영구 잔류)가
    /// 리뷰에서 드러나지 않는다. `bridges.remove`의 원자성이 이중 정리를 막으므로, non-null을 받은
    /// 경로만 후속 정리(엔진 스트림 완료·메트릭)를 수행한다.
    private SessionBridge detach(String sessionId) {
        SessionBridge bridge = bridges.remove(sessionId);
        if (bridge != null) {
            rooms.leave(bridge.room(), sessionId);
        }
        return bridge;
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("ws close failed session={}", session.getId(), e);
        }
    }

    private static void completeQuietly(StreamObserver<ClientFrame> toEngine) {
        try {
            toEngine.onCompleted();
        } catch (RuntimeException e) {
            // 이미 종료된 스트림이면 정상. 세션 정리를 막지 않도록 흡수하되, 예기치 못한 상태 진단을 위해 기록.
            log.debug("completeQuietly 무시 — 스트림이 이미 종료된 것으로 보임", e);
        }
    }

    /// `session`은 **데코레이터로 감싼** 세션이다(원본이 아니다) — 게이트웨이가 이 세션에 쓰는 모든
    /// 경로가 §D-6의 직렬화를 통과하도록, 원본 참조를 여기 보관하지 않는다.
    private record SessionBridge(
            RoomId room,
            SessionRole role,
            WebSocketSession session,
            StreamObserver<ClientFrame> toEngine) {
    }
}

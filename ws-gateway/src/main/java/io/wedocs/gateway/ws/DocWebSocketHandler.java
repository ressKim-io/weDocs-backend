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

    /// 데코레이터 송신 시간 상한.
    ///
    /// ⚠️ **이 값은 진행 중인 write를 중단시키지 않는다.** 실측(spring-websocket 7.0.8 바이트코드):
    /// `checkSessionLimits()`는 `tryFlushMessageBuffer()`가 **false를 반환한** 분기에서만 호출된다.
    /// 즉 상한 위반을 발화시키는 것은 flush 락을 **못 잡은 다른** 스레드이고, delegate write에 실제로
    /// 막혀 있는 스레드는 이 상한과 무관하게 계속 막혀 있다(그쪽의 실질 상한은 컨테이너의
    /// blocking send timeout이다).
    ///
    /// 그래서 이 값의 실제 성질은 "느린 세션을 **다음 발신자가** 발견해 끊는 임계"다.
    /// fan-out 스레드(= 발신자의 WS 인바운드 스레드)가 느린 peer에 붙잡히는 문제는 이 값으로 해결되지
    /// **않는다** — 처방은 fan-out을 발신자 스레드에서 떼어내는 것이고 그건 Phase 4 몫이다.
    /// 이 구분을 지우면 Phase 4가 숫자만 재조정하고 진짜 처방을 건너뛴다.
    ///
    /// ⚠️ 임시값 — Phase 4에서 정량화.
    static final int SEND_TIME_LIMIT_MS = 10_000;

    /// awareness 페이로드 크기 상한(secure-coding.md P2 · 가드레일 8: 신규 수신 경로는 상한 동반).
    ///
    /// WS 프레임 상한(≈4MiB)이 이미 이 경로를 묶고 있지만 그 값은 **문서 sync** 크기에 맞춰진 것이다.
    /// awareness는 게이트웨이에서 **N배로 증폭되는 유일한 인바운드 경로**라 같은 상한을 쓸 수 없다 —
    /// 4MiB 프레임 하나가 N명에게 복제되면 룸 전체 세션이 송신 큐 상한에 걸려 **함께 끊긴다**.
    /// 즉 악의적 클라이언트 1명이 프레임 한 개로 룸을 비울 수 있다.
    ///
    /// 실제 y-protocols awareness 상태는 clientId·clock·`{name, color}` JSON으로 수백 바이트다.
    /// 64KiB는 그보다 두 자리 넉넉하므로 정상 트래픽을 자르지 않는다.
    ///
    /// ⚠️ 이것은 **크기** 상한이다. **빈도** 제어(N ms 윈도우 coalescing)는 Phase 4 몫이다.
    static final int MAX_AWARENESS_PAYLOAD_BYTES = 64 * 1024;

    /// join 시 질의할 기존 peer 수 상한(§1.3).
    ///
    /// **원리상 1명으로 충분하다** — y-websocket의 queryAwareness 핸들러는 자기 상태가 아니라
    /// `awareness.getStates()` **전체**(자기가 아는 모든 clientId)를 인코딩해 응답한다
    /// (실측 2026-08-07 `y-websocket.js:53~68`). 즉 **응답 하나가 룸의 awareness 전경을 담는다.**
    ///
    /// 그런데도 1이 아닌 이유: 방금 들어와 아직 아무 awareness도 받지 못한 peer를 고르면 응답이
    /// 자기 자신뿐인 부분 답이 된다. 소수를 질의하면 그 확률이 곱으로 떨어진다.
    ///
    /// **전원(N명)을 질의하지 않는 것이 이 상한의 본론이다.** 응답은 질의자를 특정할 수 없어 룸
    /// 전원에게 릴레이되므로, N명 질의는 N개 응답 × N명 = **N² 프레임 버스트**가 된다. 재접속을
    /// 반복하면 그 버스트를 반복 유발할 수 있고, 그건 `MAX_AWARENESS_PAYLOAD_BYTES`가 막겠다고
    /// 선언한 시나리오("클라이언트 1명이 룸을 비운다")를 **join 축으로 재현**하는 것이다.
    /// 상한을 두면 버스트가 O(K·N)으로 선형화된다.
    ///
    /// 계획서는 이 N²를 Phase 3 §3.2 각주에서 "Phase 4 coalescing 확인 대상"으로만 다뤘다.
    /// per-doc 세션 캡이 없는 Phase 1에서 N에 상한이 없으므로 여기서 선형화해 둔다.
    static final int MAX_QUERIED_PEERS_ON_JOIN = 3;

    /// `sendToPeers`의 대상 수 무제한 — 릴레이는 룸 전원에게 가야 한다(질의와 달리 표본이 아니다).
    private static final int ALL_PEERS = Integer.MAX_VALUE;

    private final EngineClient engineClient;
    private final SessionMetrics sessionMetrics;
    private final YProtocolCodec codec = new YProtocolCodec();
    private final Map<String, SessionBridge> bridges = new ConcurrentHashMap<>();
    /// decorator의 message callback은 sendMessage 호출 스레드에서 동기 실행된다. 스레드별 marker로
    /// 현재 호출이 실제 queue에 수락됐는지 구분한다(terminal decorator의 무음 return은 false).
    private final ThreadLocal<Boolean> sendAccepted = new ThreadLocal<>();
    /// `bridges`의 룸 역인덱스 — awareness fan-out 대상 조회용. 두 map의 정합성은 best-effort다.
    private final RoomRegistry rooms = new RoomRegistry();

    public DocWebSocketHandler(EngineClient engineClient, SessionMetrics sessionMetrics) {
        this.engineClient = engineClient;
        this.sessionMetrics = sessionMetrics;
        // 룸 점유 게이지는 인덱스 소유자가 등록한다 — 게이지는 대상을 약참조하므로 강참조를 쥔 쪽이
        // 등록해야 값이 살아 있다. (`rooms`는 인스턴스 초기화자에서 이미 생성됐다)
        sessionMetrics.bindRoomGauges(rooms);
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
        SessionBridge bridge = new SessionBridge(roomId, role.get(), guarded, new SessionLifecycle());
        // OPENING bridge를 observer에 넘기기 전에 게시한다. room에는 아직 넣지 않으므로 fan-out 대상은
        // 아니지만, openSync 내부의 동기 terminal callback과 WS close가 같은 lifecycle을 찾을 수 있다.
        bridges.put(session.getId(), bridge);
        // SerializingStreamObserver로 감싸 gRPC 응답 콜백의 onNext/onError/onCompleted 직렬화를 강제한다.
        // gRPC 런타임이 순차 호출을 보장하지만, 이중 완료 방지 및 구현 버그 방어를 위한 안전망(Req 11.2, 11.3).
        StreamObserver<ServerFrame> toClient =
                new SerializingStreamObserver<>(engineResponseObserver(bridge));
        // wire/log 경계마다 .value()로 언랩 — RoomId는 gateway 내부로 관통하고 String이 필요한 sink에서만 푼다.
        StreamObserver<ClientFrame> toEngine;
        try {
            toEngine = engineClient.openSync(roomId.value(), role.get().wireValue(), toClient);
        } catch (RuntimeException e) {
            LogEvents.event(log, GatewayLogEvent.SESSION_OPEN_FAILED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .attr(LogFields.DOC_ID, roomId.value())
                    .errorType(GatewayErrorType.ENGINE_UNAVAILABLE)
                    .cause(e)
                    .log();
            endSession(bridge, CloseStatus.SERVER_ERROR.withReason("engine unavailable"));
            return;
        }
        // openSync 내부 callback이 먼저 terminal 상태를 선점했다면 room 등록을 건너뛰고, 방금 반환된
        // request observer만 monitor 밖에서 완료한다. callback과 activation 중 하나만 room을 소유한다.
        if (!activateBridge(bridge, toEngine)) {
            completeQuietly(toEngine);
            return;
        }
        // 신규 접속자가 기존 peer의 커서를 **즉시** 보게 한다(§1.3). 등록 이후에 호출한다 —
        // 자기 자신은 대상에서 빠지고(아직 awareness 상태가 없다), 대상 세션의 send 실패는
        // sendBinary가 그 세션 단위로 처리하므로 신규 세션 수립을 실패시키지 않는다.
        queryExistingPeers(roomId, session.getId());
    }

    /// OPENING bridge를 활성화한다. lifecycle monitor 안에서는 인메모리 인덱스만 바꾸며 I/O를 하지 않는다.
    private boolean activateBridge(SessionBridge bridge, StreamObserver<ClientFrame> toEngine) {
        synchronized (bridge.lifecycle()) {
            if (bridge.lifecycle().terminated || bridges.get(bridge.session().getId()) != bridge) {
                return false;
            }
            rooms.join(bridge.room(), bridge.session().getId());
            bridge.lifecycle().registered = true;
            bridge.lifecycle().toEngine = toEngine;
            return true;
        }
    }

    /// 세션의 아웃바운드를 큐잉·직렬화하는 데코레이터로 감싼다 — 클래스 주석 §D-6의 처방.
    /// message callback은 queue 수락 뒤에만 호출되므로 terminal 상태의 무음 거절과 정상 반환을 구분한다.
    WebSocketSession guardSends(WebSocketSession session) {
        ConcurrentWebSocketSessionDecorator guarded = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT_BYTES);
        guarded.setMessageCallback(message -> sendAccepted.set(true));
        return guarded;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] payload = toBytes(message.getPayload());
        String sessionId = session.getId();
        // awareness는 엔진을 통과하지 않는다(SDD §6.1, M3 판단 1) — 룸 릴레이로 끝난다.
        // 이 판정은 top-level 타입 varUint 하나만 읽으므로 sync 경로 비용에 영향이 없다.
        SessionBridge sender = bridges.get(sessionId);
        if (sender == null || sender.toEngine() == null) {
            return; // 이미 정리됐거나 아직 OPENING인 세션 — 엔진·룸 어느 쪽에도 전달하지 않는다.
        }
        Optional<byte[]> awareness;
        try {
            awareness = codec.decodeAwareness(payload);
        } catch (RuntimeException e) {
            // 프레이밍이 깨진 awareness. 손상 프레임 한 개로 세션을 죽이지 않는다.
            frameDropped(sessionId, sender.room(), e);
            return;
        }
        if (awareness.isPresent()) {
            relayAwareness(sender, sessionId, awareness.get());
            return;
        }
        forwardToEngine(sessionId, payload);
    }

    /// 브라우저 → 엔진(sync 전용).
    ///
    /// [Req 11.2 원자성 보장] get+onNext를 computeIfPresent로 원자화한다.
    /// ConcurrentHashMap의 키 단위 락이 afterConnectionClosed/endSession의 remove와 상호 배제
    /// → request StreamObserver에 onNext와 onCompleted가 동시 호출되지 않음(grpc-java 계약: 동시 호출 금지).
    /// 이것이 non-atomic check-then-act를 방지하는 핵심 패턴이다: "브리지 존재 확인 → onNext 호출"을
    /// 단일 원자 연산으로 합쳐 remove와의 경합을 제거한다. (§D-6 확장)
    ///
    /// ⚠️ awareness fan-out을 이 람다 **안에** 넣지 말 것 — CHM 키 락을 쥔 채 다른 세션에 I/O를 하게 되고,
    /// 그러면 (a) VT pinning, (b) 같은 룸 세션끼리 지연 전파, (c) 대상 세션 종료(→`detach`)가 다른 키의
    /// 락을 요구하는 형태가 된다.
    private void forwardToEngine(String sessionId, byte[] payload) {
        bridges.computeIfPresent(sessionId, (id, bridge) -> {
            StreamObserver<ClientFrame> toEngine = bridge.toEngine();
            if (toEngine == null) {
                return bridge; // OPENING — activation 전 프레임은 엔진에 보낼 수 없다.
            }
            try {
                codec.decodeInbound(payload, bridge.room().value())
                        .filter(frame -> isPermitted(frame, bridge, id))
                        .ifPresent(toEngine::onNext);
            } catch (RuntimeException e) {
                // 손상 프레임 한 개로 세션을 죽이지 않는다 — 그 프레임만 무시(엔진의 손상 update 처리와 대칭).
                frameDropped(id, bridge.room(), e);
            }
            return bridge;
        });
    }

    private void frameDropped(String sessionId, RoomId room, RuntimeException cause) {
        LogEvents.event(log, GatewayLogEvent.FRAME_DROPPED)
                .attr(LogFields.SESSION_ID, sessionId)
                .attr(LogFields.DOC_ID, room.value())
                .errorType(GatewayErrorType.MALFORMED_FRAME)
                .cause(cause)
                .log();
    }

    /// awareness를 같은 룸의 **다른** 세션에 릴레이한다. 엔진을 경유하지 않는다(판단 1).
    ///
    /// **self-echo를 제외한다**(§1.4) — 발신자는 자기 상태를 이미 알고, 되돌리면 클라이언트가 자기
    /// clientId 항목을 무의미하게 재적용한다.
    ///
    /// **viewer의 awareness는 허용한다**(§1.4). `isPermitted`가 막는 것은 update 페이로드, 즉 문서
    /// 변경이다. 읽는 사람의 커서가 보이는 것은 정상 동작이고, viewer를 숨기면 "누가 보고 있는지 모른 채
    /// 편집하는" 상태가 되어 협업 맥락이 오히려 손상된다. 그래서 이 경로는 `role`을 보지 않는다 —
    /// 누락이 아니라 결정이다.
    private void relayAwareness(SessionBridge sender, String senderId, byte[] payload) {
        if (payload.length > MAX_AWARENESS_PAYLOAD_BYTES) {
            // fan-out 증폭 차단. 정상 클라이언트는 여기 닿지 않는다(실제 상태는 수백 바이트).
            sessionMetrics.awarenessDropped(SessionMetrics.REASON_TOO_LARGE);
            LogEvents.event(log, GatewayLogEvent.AWARENESS_DROPPED)
                    .attr(LogFields.SESSION_ID, senderId)
                    .attr(LogFields.DOC_ID, sender.room().value())
                    .errorType(GatewayErrorType.AWARENESS_TOO_LARGE)
                    .log();
            return;
        }
        sessionMetrics.awarenessRelayed(
                sendToPeers(sender.room(), senderId, codec.encodeAwareness(payload), ALL_PEERS));
    }

    /// 룸에 새로 들어온 세션이 **기존 peer의 커서를 즉시 보게** 한다(§1.3).
    ///
    /// 게이트웨이가 질의자가 되는 이유: y-websocket은 queryAwareness를 WS로 보내지 않는다(실측).
    /// 릴레이로 구현하면 dead code이고, 신규 접속자는 기존 peer가 다음에 움직일 때까지 그를 보지
    /// 못한다(하트비트 폴백 최악 ~15초, 가만히 있는 peer는 존재조차 드러나지 않는다).
    ///
    /// 게이트웨이는 여기서도 상태를 보관하지 않는다 — 캐시를 재생하는 것이 아니라 **peer들에게
    /// 되묻는 것**이다. 룸별 최신 awareness를 인메모리 캐시하는 대안은 그 캐시를 채우려면 페이로드를
    /// 디코딩해야 하므로 §1.2의 무해석 불변식이 깨진다(기각).
    ///
    /// ⚠️ **인스턴스 로컬이다.** 게이트웨이 2대에서는 다른 인스턴스의 peer에게 닿지 않는다 —
    /// 그 확장이 Phase 3의 `awareness:query` 채널이다. Phase 1이 닫는 것은 단일 인스턴스에서의
    /// join 시 peer 발견까지다.
    /// ⚠️ 전원이 아니라 **최대 `MAX_QUERIED_PEERS_ON_JOIN`명**에게만 질의한다 — 응답 하나가 룸의
    /// awareness 전경을 담으므로 전원 질의는 N² 버스트만 만든다(상수 주석에 상세).
    private void queryExistingPeers(RoomId room, String newSessionId) {
        sessionMetrics.awarenessQuerySent(sendToPeers(
                room, newSessionId, codec.encodeQueryAwareness(), MAX_QUERIED_PEERS_ON_JOIN));
    }

    /// 룸의 세션 중 `exceptSessionId`를 뺀 대상에게 프레임을 보낸다(최대 `maxTargets`명).
    /// 반환값 = 실제로 송신이 수락된 세션 수.
    ///
    /// 룸 스냅샷을 순회하므로, 전송 실패로 대상 세션이 종료(→`endSession`→`detach`→`rooms.leave`)돼도
    /// 순회가 안전하다. 그리고 그 종료는 **대상 세션에만** 적용된다 — 수신자 한 명의 실패가 발신자나
    /// 룸의 나머지에게 번지지 않는다.
    ///
    /// 룸 인덱스엔 있으나 `bridges`에 없는 세션은 skip한다 — 두 map의 정합성은 best-effort이고(§1.2)
    /// awareness의 한 프레임 유실은 다음 커서 이동이 덮는다.
    private int sendToPeers(RoomId room, String exceptSessionId, byte[] frame, int maxTargets) {
        int sent = 0;
        for (String peerId : rooms.sessionsIn(room)) {
            if (sent >= maxTargets) {
                break;
            }
            if (peerId.equals(exceptSessionId)) {
                continue;
            }
            SessionBridge peer = bridges.get(peerId);
            if (peer == null) {
                // 두 map의 정합성이 실제로 벌어진 횟수. 로그는 남기지 않는다 — best-effort의
                // 정상적 결과라 로그로 남기면 소음이 된다. 지속적으로 크면 가정 재검토 신호(§1.2).
                sessionMetrics.awarenessDropped(SessionMetrics.REASON_SESSION_GONE);
                continue;
            }
            if (sendBinary(peer.session(), frame)) {
                sent++;
            }
        }
        return sent;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        finishCleanup(detach(session.getId()));
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
        // 프레임을 보내는 것을 방지한다. lifecycle terminal 전이가 이중 정리를 막는다.
        finishCleanup(detach(session.getId()));
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
    private StreamObserver<ServerFrame> engineResponseObserver(SessionBridge bridge) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerFrame frame) {
                codec.encodeOutbound(frame).ifPresent(bytes -> sendBinary(bridge.session(), bytes));
            }

            @Override
            public void onError(Throwable t) {
                LogEvents.event(log, GatewayLogEvent.ENGINE_STREAM_FAILED)
                        .attr(LogFields.SESSION_ID, bridge.session().getId())
                        .attr(LogFields.DOC_ID, bridge.room().value())
                        .errorType(GatewayErrorType.ENGINE_STREAM_ERROR)
                        .cause(t)
                        .log();
                endSession(bridge, CloseStatus.SERVER_ERROR);
            }

            @Override
            public void onCompleted() {
                endSession(bridge, CloseStatus.NORMAL);
            }
        };
    }

    /// 반환값 = **decorator queue가 송신을 수락했는가**. "상대에게 도달했는가"가 아니다.
    /// terminal decorator는 예외 없이 return하므로, 단순 정상 반환이 아니라 message callback marker를 본다.
    boolean sendBinary(WebSocketSession session, byte[] bytes) {
        sendAccepted.set(false);
        try {
            session.sendMessage(new BinaryMessage(bytes));
            return Boolean.TRUE.equals(sendAccepted.get());
        } catch (SessionLimitExceededException e) {
            // 데코레이터의 buffer 또는 send-time 상한 초과. Spring이 같은 예외 타입을 쓰므로
            // 세부 원인을 추측하지 않고 aggregate send-limit 계약으로 기록한다.
            sessionMetrics.sendLimitExceeded();
            LogEvents.event(log, GatewayLogEvent.SEND_LIMIT_EXCEEDED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .errorType(GatewayErrorType.SEND_LIMIT_EXCEEDED)
                    .cause(e)
                    .log();
            endSession(session, CloseStatus.SERVER_ERROR);
            return false;
        } catch (IOException e) {
            LogEvents.event(log, GatewayLogEvent.SEND_FAILED)
                    .attr(LogFields.SESSION_ID, session.getId())
                    .errorType(GatewayErrorType.SEND_FAILED)
                    .cause(e)
                    .log();
            endSession(session, CloseStatus.SERVER_ERROR);
            return false;
        } finally {
            sendAccepted.remove();
        }
    }

    /// 엔진이 스트림을 끝냈거나 WS send가 실패했을 때 WS를 닫는다. terminal 전이를 먼저 선점해
    /// afterConnectionClosed와 중복 정리를 막고, gRPC 완료·WS close·메트릭은 lifecycle monitor 밖에서 수행한다.
    private void endSession(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = bridges.get(session.getId());
        finishCleanup(bridge == null ? null : detach(bridge));
        closeQuietly(session, status);
    }

    /// 엔진 callback은 자신이 생성된 정확한 bridge를 종료한다. 같은 session id가 재사용되더라도
    /// 늦은 callback이 새 bridge를 제거하지 않도록 조건부 remove를 사용한다.
    private void endSession(SessionBridge bridge, CloseStatus status) {
        finishCleanup(detach(bridge));
        closeQuietly(bridge.session(), status);
    }

    /// 세션을 `bridges`와 룸 인덱스에서 **하나의 lifecycle 전이로** 떼어낸다.
    /// monitor 안에서는 map/index 상태만 바꾸고, gRPC·WS I/O와 메트릭은 `finishCleanup`에서 수행한다.
    private SessionCleanup detach(String sessionId) {
        SessionBridge bridge = bridges.get(sessionId);
        return bridge == null ? null : detach(bridge);
    }

    private SessionCleanup detach(SessionBridge bridge) {
        SessionLifecycle lifecycle = bridge.lifecycle();
        synchronized (lifecycle) {
            if (lifecycle.terminated) {
                return null;
            }
            lifecycle.terminated = true;
            if (!bridges.remove(bridge.session().getId(), bridge)) {
                return null;
            }
            boolean registered = lifecycle.registered;
            if (registered) {
                rooms.leave(bridge.room(), bridge.session().getId());
            }
            lifecycle.registered = false;
            StreamObserver<ClientFrame> toEngine = lifecycle.toEngine;
            lifecycle.toEngine = null;
            return new SessionCleanup(toEngine, registered);
        }
    }

    private void finishCleanup(SessionCleanup cleanup) {
        if (cleanup == null) {
            return;
        }
        if (cleanup.toEngine() != null) {
            completeQuietly(cleanup.toEngine());
        }
        if (cleanup.registered()) {
            sessionMetrics.sessionClosed();
        }
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
            SessionLifecycle lifecycle) {

        StreamObserver<ClientFrame> toEngine() {
            return lifecycle.toEngine;
        }
    }

    /// OPENING → ACTIVE 또는 TERMINATED 전이를 조정하는 세션별 상태. 필드 접근은 lifecycle monitor
    /// 아래에서 수행하되, `toEngine` 읽기는 inbound hot path에서 monitor를 잡지 않도록 volatile이다.
    private static final class SessionLifecycle {

        private volatile StreamObserver<ClientFrame> toEngine;
        private boolean registered;
        private boolean terminated;
    }

    /// lifecycle monitor에서 꺼낸 정리 작업. 외부 I/O는 이 불변 값으로 monitor 밖에서 실행한다.
    private record SessionCleanup(StreamObserver<ClientFrame> toEngine, boolean registered) {
    }
}

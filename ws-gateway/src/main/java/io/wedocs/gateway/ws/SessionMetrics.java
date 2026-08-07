package io.wedocs.gateway.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.wedocs.gateway.common.logging.GatewayErrorType;
import org.springframework.stereotype.Component;

/// 수립된 WS 세션의 관측 메트릭 (ADR-0021 §관측 계약). 핸드셰이크 단계 메트릭(`AuthMetrics`)과 분리한다 —
/// 소유 패키지가 다르고(`ws` ↔ `auth`), 한쪽이 다른 쪽을 참조하면 패키지 순환이 생긴다.
@Component
public class SessionMetrics {

    static final String WRITE_DROPPED = "ws.write.dropped";   // → ws_write_dropped_total
    static final String SESSION_CLOSED = "ws.session.closed"; // → ws_session_closed_total
    static final String SEND_QUEUE_EXCEEDED = "ws.send.queue.exceeded"; // → ws_send_queue_exceeded_total

    // ── awareness (M3 Phase 1, plan §1.6) ──
    static final String AWARENESS_RELAYED = "ws.awareness.relayed";
    static final String AWARENESS_DROPPED = "ws.awareness.dropped";
    static final String AWARENESS_QUERY_SENT = "ws.awareness.query_sent";
    static final String ROOM_SESSIONS_MAX = "ws.room.sessions.max";
    static final String ROOMS_ACTIVE = "ws.rooms.active";

    private static final String TAG_REASON = "reason";
    private static final String REASON_VIEWER = "viewer";

    /// awareness 드롭 사유. 값은 대응하는 `error.type` 열거값과 **동치를 유지한다** — 로그와 메트릭이
    /// 같은 문자열을 쓰지 않으면 대시보드에서 두 신호를 상관시킬 수 없다.
    static final String REASON_TOO_LARGE = GatewayErrorType.AWARENESS_TOO_LARGE.value();

    /// 룸 인덱스엔 있으나 브리지가 이미 사라진 대상. **대응하는 `error.type`이 없다** —
    /// best-effort 정합성의 정상적 결과이므로 로그를 남기지 않고(§1.2) 이 카운터로만 관측한다.
    /// 값이 지속적으로 크면 정합성 가정을 재검토할 신호다.
    static final String REASON_SESSION_GONE = "session_gone";

    private final MeterRegistry registry;

    public SessionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /// viewer 세션의 쓰기 프레임을 버린 1건. **이 값이 0이면 다층 방어가 동작 중인지 알 수 없다** —
    /// 차단은 성공해도 아무 흔적을 남기지 않는 종류의 동작이라, 계측이 없으면 "막고 있다"와
    /// "코드가 죽어 있다"가 겉으로 똑같다(secure-coding: 무신호 실패 금지).
    public void writeDropped() {
        registry.counter(WRITE_DROPPED, TAG_REASON, REASON_VIEWER).increment();
    }

    /// WS 세션이 종료된 1건 — 경로(정상 close, 전송 에러, 엔진 스트림 종료) 불문.
    /// bridges.remove()가 일어날 때마다 호출되어야 한다. 미호출 시 세션 누수를 감지할 수 없다.
    public void sessionClosed() {
        registry.counter(SESSION_CLOSED).increment();
    }

    /// 세션 송신 큐 상한 초과로 세션을 끊은 1건 = **느린 클라이언트**(M3 plan §1.6).
    /// 이 값이 오르면 상한이 낮은 것인지 클라이언트가 실제로 못 따라오는 것인지를 갈라야 한다 —
    /// Phase 4의 버퍼·시간 상한 정량화가 직접 이 신호를 근거로 삼는다.
    public void sendQueueExceeded() {
        registry.counter(SEND_QUEUE_EXCEEDED).increment();
    }

    /// awareness 릴레이 처리량 — **송신이 수락된 대상 세션 수**(fan-out 간선 수)를 더한다.
    /// "상대에게 도달한 수"가 아니다: 데코레이터가 큐잉하면 수락 시점과 전송 시점이 갈린다.
    ///
    /// 프레임 수가 아니라 간선 수인 이유: 룸이 커질수록 같은 프레임 하나의 비용이 N배가 되므로,
    /// 프레임 수만 세면 부하를 과소평가한다.
    ///
    /// ⚠️ `relayed + dropped`는 시도 횟수가 아니다. 전송 실패(끊긴 클라이언트)는 여기서 빠지고
    /// `ws.send.failed`·`ws.send.queue.exceeded`로 집계된다 — 이중 계상을 피한 결과다.
    public void awarenessRelayed(int targets) {
        registry.counter(AWARENESS_RELAYED).increment(targets);
    }

    /// awareness 프레임을 버린 1건. 세션은 유지된다(프레임 단위 드롭).
    public void awarenessDropped(String reason) {
        registry.counter(AWARENESS_DROPPED, TAG_REASON, reason).increment();
    }

    /// join 시 기존 peer에게 보낸 queryAwareness 수(§1.3).
    /// **이 값이 계속 0이면 발신 배선이 죽은 것이다** — 그때의 증상은 "신규 접속자가 최대 ~15초간
    /// 기존 커서를 못 본다"이고, 그건 에러가 아니라 체감 지연이라 계측 없이는 발견되지 않는다.
    public void awarenessQuerySent(int peers) {
        registry.counter(AWARENESS_QUERY_SENT).increment(peers);
    }

    /// 룸 점유 게이지를 등록한다. 관측 대상은 핸들러가 소유한 인덱스다 — Micrometer 게이지는 대상을
    /// 약참조하므로 소유자(싱글턴 핸들러)가 강참조를 유지하는 동안만 값이 산출된다.
    ///
    /// ⚠️ 계획서 §1.6은 `ws.room.sessions` 하나를 "룸당 세션 수 **분포**"로 적었다. 스칼라 게이지는
    /// 분포를 담을 수 없으므로, Phase 4가 실제로 필요한 두 값으로 나눴다 —
    /// `.max`(per-doc 세션 캡의 직접 근거) + 활성 룸 수(fan-out 대상 규모).
    /// 진짜 분포가 필요한 시점은 Phase 6 부하 측정이고 그때는 히스토그램이어야 한다.
    void bindRoomGauges(RoomRegistry rooms) {
        Gauge.builder(ROOM_SESSIONS_MAX, rooms, RoomRegistry::largestRoomSize)
                .description("가장 큰 룸의 세션 수 — Phase 4 per-doc 캡의 근거")
                .register(registry);
        Gauge.builder(ROOMS_ACTIVE, rooms, RoomRegistry::roomCount)
                .description("세션을 하나 이상 보유한 룸 수")
                .register(registry);
    }
}

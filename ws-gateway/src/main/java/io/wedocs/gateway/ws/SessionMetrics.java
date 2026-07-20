package io.wedocs.gateway.ws;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/// 수립된 WS 세션의 관측 메트릭 (ADR-0021 §관측 계약). 핸드셰이크 단계 메트릭(`AuthMetrics`)과 분리한다 —
/// 소유 패키지가 다르고(`ws` ↔ `auth`), 한쪽이 다른 쪽을 참조하면 패키지 순환이 생긴다.
@Component
public class SessionMetrics {

    static final String WRITE_DROPPED = "ws.write.dropped";   // → ws_write_dropped_total

    private static final String TAG_REASON = "reason";
    private static final String REASON_VIEWER = "viewer";

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
}

package io.wedocs.gateway.common.logging;

import org.slf4j.MDC;

import java.util.Optional;

/// 상관 필드 조회 — MDC만 참조한다(OTel API 컴파일 타임 의존 없음, 요구사항 8.3/11.5).
/// OTel_Javaagent의 logback-mdc 계측이 trace_id·span_id를 MDC에 주입하고, 미부착 환경에선
/// 비어 있어 `-`로 degrade한다. javaagent 부착 여부와 무관하게 같은 코드 경로가 동작한다.
///
/// 애플리케이션은 MDC에 키를 쓰지 않는다(읽기 전용) — javaagent가 채우고 요청 끝에 자동 정리한다.
/// MDC 키 집합 = {trace_id, span_id} (+ javaagent가 함께 넣는 trace_flags).
///
/// Interim_Attribute: OTLP_Log_Pipeline이 수집 경로가 되면 TraceId·SpanId가 LogRecord의
/// 1급 필드가 되고 javaagent가 로그-트레이스 상관을 자동 수행하므로, 이 명시 속성은 제거된다.
/// 제거 조건: OTLP_Log_Pipeline(M4/M5)이 유일한 수집 경로로 확정되고, 파일 tail 경로가 폐기된 시점.
public final class LogCorrelation {

    private LogCorrelation() {
    }

    /// 폴리글랏 단일 trace 상관용 trace_id — javaagent MDC에서 읽되, 없거나 공백이면 `-`.
    ///
    /// Interim_Attribute: OTLP_Log_Pipeline 도입 시 LogRecord.TraceId로 대체되므로 제거된다.
    public static String traceId() {
        String value = MDC.get(LogFields.TRACE_ID);
        return (value != null && !value.isBlank()) ? value : LogFields.NONE;
    }

    /// span_id — MDC에 값이 있을 때만 반환한다. 없거나 공백이면 Optional.empty().
    /// emitter는 empty일 때 KVP를 추가하지 않는다(trace_id와 달리 폴백 `-`를 넣지 않는다).
    ///
    /// Interim_Attribute: OTLP_Log_Pipeline 도입 시 LogRecord.SpanId로 대체되므로 제거된다.
    public static Optional<String> spanId() {
        String value = MDC.get(LogFields.SPAN_ID);
        return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
    }

    /// emitter의 중복 방지 판정 — MDC에 trace_id가 이미 있으면 true.
    /// true이면 encoder가 MDC를 평면 멤버로 이미 쓰므로 KVP에 trace_id를 추가하지 않는다.
    /// false이면 KVP로 `trace_id = "-"` 폴백을 넣어 한 라인에 정확히 1회 출현을 보장한다.
    ///
    /// Interim_Attribute: OTLP_Log_Pipeline 도입 시 이 판정 자체가 불필요해지므로 제거된다.
    public static boolean mdcHasTraceId() {
        String value = MDC.get(LogFields.TRACE_ID);
        return value != null && !value.isBlank();
    }
}

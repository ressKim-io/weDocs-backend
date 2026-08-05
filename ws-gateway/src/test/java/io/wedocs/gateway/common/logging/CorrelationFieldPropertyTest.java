package io.wedocs.gateway.common.logging;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 8.1, 8.2, 8.5, 8.9**
///
/// Property 12: 상관 필드 단일 출현과 폴백 —
/// MDC 상태 조합(trace_id: 존재·부재·공백 × span_id: 존재·부재)에 대해
/// emitter가 정확히 1회 출현 규칙과 폴백 규약을 준수함을 확인한다.
/// 테스트 환경은 javaagent 미부착(MDC를 수동 설정).
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 12: 상관 필드 단일 출현과 폴백")
class CorrelationFieldPropertyTest {

    private static final org.slf4j.Logger EMIT_LOGGER =
            LoggerFactory.getLogger(CorrelationFieldPropertyTest.class);

    /// MDC trace_id 상태: present(비공백 값), absent(null), blank("")
    enum TraceIdState { PRESENT, ABSENT, BLANK }

    /// MDC span_id 상태: present(비공백 값), absent(null)
    enum SpanIdState { PRESENT, ABSENT }

    record MdcCombination(TraceIdState traceIdState, String traceIdValue,
                          SpanIdState spanIdState, String spanIdValue) {}

    @Property(tries = 100)
    void traceId_appearsExactlyOnceAcrossMdcAndKvp(@ForAll("mdcCombinations") MdcCombination combo) {
        try (var logs = CapturedLogs.of(CorrelationFieldPropertyTest.class)) {
            applyMdc(combo);
            try {
                LogEvents.event(EMIT_LOGGER, GatewayLogEvent.FRAME_ANOMALY)
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .log();
            } finally {
                clearMdc();
            }

            var event = logs.events().getFirst();

            boolean inMdc = event.mdc().containsKey(LogFields.TRACE_ID)
                    && !event.mdc().get(LogFields.TRACE_ID).isBlank();
            boolean inKvp = event.hasKey(LogFields.TRACE_ID);

            // trace_id는 정확히 한 곳에만 존재해야 한다
            assertThat(inMdc || inKvp)
                    .as("trace_id must appear in at least one of MDC or KVP (combo=%s)", combo)
                    .isTrue();
            assertThat(inMdc && inKvp)
                    .as("trace_id must NOT appear in both MDC and KVP (combo=%s)", combo)
                    .isFalse();
        }
    }

    @Property(tries = 100)
    void traceId_fallbackWhenMdcEmpty(@ForAll("mdcCombinations") MdcCombination combo) {
        try (var logs = CapturedLogs.of(CorrelationFieldPropertyTest.class)) {
            applyMdc(combo);
            try {
                LogEvents.event(EMIT_LOGGER, GatewayLogEvent.FRAME_ANOMALY)
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .log();
            } finally {
                clearMdc();
            }

            var event = logs.events().getFirst();
            boolean mdcHasValidTraceId = event.mdc().containsKey(LogFields.TRACE_ID)
                    && !event.mdc().get(LogFields.TRACE_ID).isBlank();

            if (!mdcHasValidTraceId) {
                // MDC에 유효한 trace_id가 없으면 KVP에 "-" 폴백이 있어야 한다
                assertThat(event.hasKey(LogFields.TRACE_ID))
                        .as("KVP must contain trace_id fallback when MDC is empty (combo=%s)", combo)
                        .isTrue();
                assertThat(event.getString(LogFields.TRACE_ID))
                        .as("KVP trace_id fallback must be '-' (combo=%s)", combo)
                        .isEqualTo(LogFields.NONE);
            }
        }
    }

    @Property(tries = 100)
    void traceId_notInKvpWhenMdcHasIt(@ForAll("mdcCombinations") MdcCombination combo) {
        try (var logs = CapturedLogs.of(CorrelationFieldPropertyTest.class)) {
            applyMdc(combo);
            try {
                LogEvents.event(EMIT_LOGGER, GatewayLogEvent.FRAME_ANOMALY)
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .log();
            } finally {
                clearMdc();
            }

            var event = logs.events().getFirst();
            boolean mdcHasValidTraceId = event.mdc().containsKey(LogFields.TRACE_ID)
                    && !event.mdc().get(LogFields.TRACE_ID).isBlank();

            if (mdcHasValidTraceId) {
                // MDC에 유효한 trace_id가 있으면 KVP에 추가하지 않는다(encoder가 MDC를 이미 쓴다)
                assertThat(event.hasKey(LogFields.TRACE_ID))
                        .as("KVP must NOT contain trace_id when MDC already has it (combo=%s)", combo)
                        .isFalse();
            }
        }
    }

    @Property(tries = 100)
    void spanId_neverAppearsInKvp(@ForAll("mdcCombinations") MdcCombination combo) {
        try (var logs = CapturedLogs.of(CorrelationFieldPropertyTest.class)) {
            applyMdc(combo);
            try {
                LogEvents.event(EMIT_LOGGER, GatewayLogEvent.FRAME_ANOMALY)
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .log();
            } finally {
                clearMdc();
            }

            var event = logs.events().getFirst();

            // span_id는 emitter가 KVP에 추가하지 않는다 — MDC에 있을 때만 encoder가 쓴다
            assertThat(event.hasKey(LogFields.SPAN_ID))
                    .as("span_id must NEVER appear in KVP (combo=%s)", combo)
                    .isFalse();
        }
    }

    @Property(tries = 100)
    void blankTraceId_treatedAsAbsent(@ForAll("blankTraceIdCombinations") MdcCombination combo) {
        try (var logs = CapturedLogs.of(CorrelationFieldPropertyTest.class)) {
            applyMdc(combo);
            try {
                LogEvents.event(EMIT_LOGGER, GatewayLogEvent.FRAME_ANOMALY)
                        .errorType(GatewayErrorType.MALFORMED_FRAME)
                        .log();
            } finally {
                clearMdc();
            }

            var event = logs.events().getFirst();

            // 공백 trace_id는 부재와 동일하게 KVP 폴백 "-"를 넣는다
            assertThat(event.hasKey(LogFields.TRACE_ID))
                    .as("KVP must contain trace_id fallback when MDC trace_id is blank")
                    .isTrue();
            assertThat(event.getString(LogFields.TRACE_ID))
                    .isEqualTo(LogFields.NONE);
        }
    }

    @Provide
    Arbitrary<MdcCombination> mdcCombinations() {
        Arbitrary<TraceIdState> traceIdStates = Arbitraries.of(TraceIdState.values());
        Arbitrary<String> traceIdValues = Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .ofLength(32)
                .map(s -> s.isEmpty() ? "abcdef0123456789abcdef0123456789" : s);
        Arbitrary<SpanIdState> spanIdStates = Arbitraries.of(SpanIdState.values());
        Arbitrary<String> spanIdValues = Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .ofLength(16)
                .map(s -> s.isEmpty() ? "abcdef0123456789" : s);

        return Combinators.combine(traceIdStates, traceIdValues, spanIdStates, spanIdValues)
                .as(MdcCombination::new);
    }

    @Provide
    Arbitrary<MdcCombination> blankTraceIdCombinations() {
        Arbitrary<SpanIdState> spanIdStates = Arbitraries.of(SpanIdState.values());
        Arbitrary<String> spanIdValues = Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .ofLength(16)
                .map(s -> s.isEmpty() ? "abcdef0123456789" : s);

        return Combinators.combine(
                Arbitraries.just(TraceIdState.BLANK),
                Arbitraries.just(""),
                spanIdStates,
                spanIdValues
        ).as(MdcCombination::new);
    }

    private void applyMdc(MdcCombination combo) {
        switch (combo.traceIdState()) {
            case PRESENT -> MDC.put(LogFields.TRACE_ID, combo.traceIdValue());
            case ABSENT -> MDC.remove(LogFields.TRACE_ID);
            case BLANK -> MDC.put(LogFields.TRACE_ID, "");
        }
        switch (combo.spanIdState()) {
            case PRESENT -> MDC.put(LogFields.SPAN_ID, combo.spanIdValue());
            case ABSENT -> MDC.remove(LogFields.SPAN_ID);
        }
    }

    private void clearMdc() {
        MDC.remove(LogFields.TRACE_ID);
        MDC.remove(LogFields.SPAN_ID);
    }
}

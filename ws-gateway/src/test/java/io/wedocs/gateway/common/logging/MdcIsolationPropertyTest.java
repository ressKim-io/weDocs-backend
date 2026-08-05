package io.wedocs.gateway.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 2.1, 2.4, 2.5, 2.6**
///
/// Property 13: MDC 격리와 키 집합 분리 —
/// 임의 상관 필드 조합에서 MDC 키 집합과 KVP 키 집합의 교집합이 공집합임을 확인하고,
/// 스레드 재사용 시뮬레이션을 통해 MDC 정리 후 누수가 없음을 검증한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 13: MDC 격리와 키 집합 분리")
class MdcIsolationPropertyTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(MdcIsolationPropertyTest.class);

    private Level originalLevel;

    @BeforeProperty
    void enableAllLevels() {
        Logger logbackLogger = (Logger) LOGGER;
        originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.TRACE);
    }

    @AfterProperty
    void restoreLevel() {
        Logger logbackLogger = (Logger) LOGGER;
        logbackLogger.setLevel(originalLevel);
        MDC.clear();
    }

    /// MDC 비어 있을 때 — trace_id가 KVP 폴백("-")으로 들어가고, MDC 키 집합이 비어 있으므로
    /// 교집합은 당연히 공집합이다.
    @Property(tries = 100)
    void mdcEmpty_kvpAndMdcKeysAreDisjoint(@ForAll("gatewayEvents") GatewayLogEvent taxonomy) {
        MDC.clear();
        try (var logs = CapturedLogs.of(MdcIsolationPropertyTest.class)) {
            LogEvents.event(LOGGER, taxonomy)
                    .attr(LogFields.DOC_ID, "doc-test")
                    .attr(LogFields.USER_HASH, "abcde12345")
                    .attr(LogFields.ERROR_TYPE, "test_error")
                    .log();

            var events = logs.events();
            assertThat(events).isNotEmpty();

            var event = events.getFirst();
            Set<String> mdcKeys = event.mdc().keySet();
            Set<String> kvpKeys = event.kvp().stream()
                    .map(p -> p.key)
                    .collect(Collectors.toSet());

            // MDC is empty, so intersection is trivially empty
            assertThat(mdcKeys).isEmpty();
            // trace_id should appear in KVP as fallback
            assertThat(kvpKeys).contains(LogFields.TRACE_ID);
            // Disjointness: intersection must be empty
            Set<String> intersection = mdcKeys.stream()
                    .filter(kvpKeys::contains)
                    .collect(Collectors.toSet());
            assertThat(intersection)
                    .as("MDC keys ∩ KVP keys must be empty")
                    .isEmpty();
        } finally {
            MDC.clear();
        }
    }

    /// MDC에 trace_id가 있을 때 — emitter는 KVP에 trace_id를 추가하지 않으므로
    /// trace_id는 MDC에만 존재하고 KVP에는 없다 → 교집합 = 공집합.
    @Property(tries = 100)
    void mdcHasTraceId_kvpAndMdcKeysAreDisjoint(
            @ForAll("traceIds") String traceId,
            @ForAll("gatewayEvents") GatewayLogEvent taxonomy) {
        MDC.clear();
        MDC.put(LogFields.TRACE_ID, traceId);
        try (var logs = CapturedLogs.of(MdcIsolationPropertyTest.class)) {
            LogEvents.event(LOGGER, taxonomy)
                    .attr(LogFields.DOC_ID, "doc-test")
                    .attr(LogFields.USER_HASH, "abcde12345")
                    .attr(LogFields.ERROR_TYPE, "test_error")
                    .log();

            var events = logs.events();
            assertThat(events).isNotEmpty();

            var event = events.getFirst();
            Set<String> mdcKeys = event.mdc().keySet();
            Set<String> kvpKeys = event.kvp().stream()
                    .map(p -> p.key)
                    .collect(Collectors.toSet());

            // trace_id should be in MDC but NOT in KVP
            assertThat(mdcKeys).contains(LogFields.TRACE_ID);
            assertThat(kvpKeys).doesNotContain(LogFields.TRACE_ID);

            // Disjointness: intersection must be empty
            Set<String> intersection = mdcKeys.stream()
                    .filter(kvpKeys::contains)
                    .collect(Collectors.toSet());
            assertThat(intersection)
                    .as("MDC keys ∩ KVP keys must be empty when MDC has trace_id")
                    .isEmpty();
        } finally {
            MDC.clear();
        }
    }

    /// MDC에 trace_id와 span_id 모두 있을 때 — {trace_id, span_id} ⊂ MDC keys이고
    /// 둘 다 KVP에 나타나지 않으므로 교집합 = 공집합.
    @Property(tries = 100)
    void mdcHasTraceIdAndSpanId_kvpAndMdcKeysAreDisjoint(
            @ForAll("traceIds") String traceId,
            @ForAll("spanIds") String spanId,
            @ForAll("gatewayEvents") GatewayLogEvent taxonomy) {
        MDC.clear();
        MDC.put(LogFields.TRACE_ID, traceId);
        MDC.put(LogFields.SPAN_ID, spanId);
        try (var logs = CapturedLogs.of(MdcIsolationPropertyTest.class)) {
            LogEvents.event(LOGGER, taxonomy)
                    .attr(LogFields.DOC_ID, "doc-test")
                    .attr(LogFields.USER_HASH, "abcde12345")
                    .attr(LogFields.ERROR_TYPE, "test_error")
                    .log();

            var events = logs.events();
            assertThat(events).isNotEmpty();

            var event = events.getFirst();
            Set<String> mdcKeys = event.mdc().keySet();
            Set<String> kvpKeys = event.kvp().stream()
                    .map(p -> p.key)
                    .collect(Collectors.toSet());

            // Both trace_id and span_id in MDC, neither in KVP
            assertThat(mdcKeys).contains(LogFields.TRACE_ID, LogFields.SPAN_ID);
            assertThat(kvpKeys).doesNotContain(LogFields.TRACE_ID, LogFields.SPAN_ID);

            // Disjointness: intersection must be empty
            Set<String> intersection = mdcKeys.stream()
                    .filter(kvpKeys::contains)
                    .collect(Collectors.toSet());
            assertThat(intersection)
                    .as("MDC keys ∩ KVP keys must be empty when MDC has trace_id+span_id")
                    .isEmpty();
        } finally {
            MDC.clear();
        }
    }

    /// 스레드 재사용 시뮬레이션 — Request 1에서 MDC에 trace_id를 설정하고 emit 후 정리하면,
    /// Request 2(같은 스레드, MDC 미설정)에서 이전 trace_id가 누수되지 않는다.
    @Property(tries = 100)
    void threadReuse_mdcClearPreventsLeakage(
            @ForAll("traceIds") String traceId1,
            @ForAll("gatewayEvents") GatewayLogEvent taxonomy) {
        MDC.clear();
        try (var logs = CapturedLogs.of(MdcIsolationPropertyTest.class)) {
            // --- Request 1: MDC has trace_id ---
            MDC.put(LogFields.TRACE_ID, traceId1);
            LogEvents.event(LOGGER, taxonomy)
                    .attr(LogFields.DOC_ID, "doc-req1")
                    .attr(LogFields.ERROR_TYPE, "test_error")
                    .log();

            // Request end: clear MDC (simulating javaagent cleanup)
            MDC.clear();

            // --- Request 2: same thread, no MDC set ---
            LogEvents.event(LOGGER, taxonomy)
                    .attr(LogFields.DOC_ID, "doc-req2")
                    .attr(LogFields.ERROR_TYPE, "test_error")
                    .log();

            var events = logs.events();
            assertThat(events).hasSize(2);

            // Request 2 event: MDC should be empty, no leakage from request 1
            var req2Event = events.get(1);
            assertThat(req2Event.mdc())
                    .as("MDC must be empty for request 2 (no leakage from request 1)")
                    .isEmpty();
            // trace_id in KVP should be fallback "-", not the previous request's value
            assertThat(req2Event.getString(LogFields.TRACE_ID))
                    .as("trace_id in KVP must be fallback '-', not leaked value")
                    .isEqualTo(LogFields.NONE);
        } finally {
            MDC.clear();
        }
    }

    /// MDC 정리 후 비어 있음 확인 — 요청 처리 끝에 MDC.clear()를 호출하면 MDC가 비어야 한다.
    @Property(tries = 100)
    void mdcEmptyAfterCleanup(
            @ForAll("traceIds") String traceId,
            @ForAll("spanIds") String spanId) {
        MDC.clear();
        try {
            MDC.put(LogFields.TRACE_ID, traceId);
            MDC.put(LogFields.SPAN_ID, spanId);

            // Verify MDC has values
            assertThat(MDC.get(LogFields.TRACE_ID)).isEqualTo(traceId);
            assertThat(MDC.get(LogFields.SPAN_ID)).isEqualTo(spanId);

            // Simulate request end: clear MDC
            MDC.clear();

            // After cleanup, MDC must be completely empty
            assertThat(MDC.getCopyOfContextMap())
                    .as("MDC must be empty after MDC.clear()")
                    .isNullOrEmpty();
        } finally {
            MDC.clear();
        }
    }

    @Provide
    Arbitrary<String> traceIds() {
        // 32-char hex string simulating OpenTelemetry trace_id format
        return Arbitraries.strings()
                .withCharRange('0', '9')
                .withCharRange('a', 'f')
                .ofLength(32);
    }

    @Provide
    Arbitrary<String> spanIds() {
        // 16-char hex string simulating OpenTelemetry span_id format
        return Arbitraries.strings()
                .withCharRange('0', '9')
                .withCharRange('a', 'f')
                .ofLength(16);
    }

    @Provide
    Arbitrary<GatewayLogEvent> gatewayEvents() {
        return Arbitraries.of(GatewayLogEvent.values());
    }
}

package io.wedocs.gateway.common.logging;

import ch.qos.logback.classic.Logger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 4.1, 5.2, 8.4, 8.8, 9.2**
///
/// Property 2: 필수 공통 속성 완비 —
/// 양 모듈 taxonomy 전수 × 임의 추가 속성 조합에 대해, emit된 로그 이벤트가
/// (1) `event.name`을 taxonomy의 `eventName()`과 일치시키고,
/// (2) `trace_id` 필드를 포함하며(MDC 비어있으면 KVP 폴백 "-"),
/// (3) taxonomy가 선언한 `requiredAttributes` 전수를 포함함을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 2: 필수 공통 속성 완비")
class EmitterRequiredAttributesPropertyTest {

    @Property(tries = 100)
    void emittedEvent_containsEventName_traceId_andAllRequiredAttributes(
            @ForAll("taxonomyWithAttributes") Tuple.Tuple2<GatewayLogEvent, Map<String, String>> input) {

        GatewayLogEvent taxonomy = input.get1();
        Map<String, String> extraAttrs = input.get2();

        // 로거 레벨을 ALL로 설정해 DEBUG/TRACE 이벤트도 캡처되게 한다.
        Logger logger = (Logger) LoggerFactory.getLogger(LogEvents.class);
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.ALL);

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents event = LogEvents.event(logger, taxonomy);

            // 필수 속성 중 error.type은 .errorType()으로, 나머지는 .attr()으로 채운다
            Set<String> required = taxonomy.requiredAttributes();
            for (String key : required) {
                if (key.equals(LogFields.ERROR_TYPE)) {
                    event.errorType(GatewayErrorType.MALFORMED_FRAME);
                } else {
                    event.attr(key, "test-value-" + key);
                }
            }

            // 임의 추가 속성
            for (var entry : extraAttrs.entrySet()) {
                event.attr(entry.getKey(), entry.getValue());
            }

            event.log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            // (1) event.name = taxonomy.eventName()
            assertThat(captured.getString(LogFields.EVENT_NAME))
                    .as("event.name must match taxonomy eventName()")
                    .isEqualTo(taxonomy.eventName());

            // (2) trace_id 존재 — MDC 비어있으므로 KVP 폴백 "-"
            assertThat(captured.hasKey(LogFields.TRACE_ID))
                    .as("trace_id must be present as KVP fallback")
                    .isTrue();
            assertThat(captured.getString(LogFields.TRACE_ID))
                    .as("trace_id KVP fallback value must be '-'")
                    .isEqualTo(LogFields.NONE);

            // (3) requiredAttributes 전수 포함
            for (String key : required) {
                assertThat(captured.hasKey(key))
                        .as("required attribute '%s' must be present for %s", key, taxonomy.name())
                        .isTrue();
            }
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Provide
    Arbitrary<Tuple.Tuple2<GatewayLogEvent, Map<String, String>>> taxonomyWithAttributes() {
        Arbitrary<GatewayLogEvent> taxonomyArb = Arbitraries.of(GatewayLogEvent.values());

        Arbitrary<Map<String, String>> attrsArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .map(s -> "extra." + s)
                .flatMap(key -> Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(10)
                        .map(val -> Map.entry(key, val)))
                .list()
                .ofMinSize(0)
                .ofMaxSize(5)
                .map(entries -> {
                    var map = new java.util.HashMap<String, String>();
                    for (var entry : entries) {
                        map.put(entry.getKey(), entry.getValue());
                    }
                    return Map.copyOf(map);
                });

        return Combinators.combine(taxonomyArb, attrsArb).as(Tuple::of);
    }
}

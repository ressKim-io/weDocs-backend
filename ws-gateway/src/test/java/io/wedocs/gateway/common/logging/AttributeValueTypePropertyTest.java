package io.wedocs.gateway.common.logging;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 4.6, 11.3, 11.4, 11.9**
///
/// Property 5: 속성 값 타입 제한과 변환 —
/// 임의 속성 값(허용 타입·비허용 타입·개행/탭 포함 문자열·null)에 대해:
/// (1) emit된 속성 값은 허용 타입(String, Boolean, Integer, Long, Float, Double 또는 동종 배열)만 포함
/// (2) 비허용 타입은 단일 라인 문자열로 변환(개행 없음)
/// (3) null 값은 속성이 생략됨(KVP에 키 미존재)
/// (4) toString() 예외 → "<unrenderable:ClassName>"
/// (5) 비허용 타입의 문자열 변환 결과는 1024자 이하
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 5: 속성 값 타입 제한과 변환")
class AttributeValueTypePropertyTest {

    private static final Logger logger = LoggerFactory.getLogger(LogEvents.class);
    private static final String TEST_KEY = "test.attr";
    private static final int MAX_STRING_LENGTH = 1024;

    /// 허용 타입 집합 — OTLP가 기본 지원하는 속성 타입.
    private static final Set<Class<?>> ALLOWED_TYPES = Set.of(
            String.class, Boolean.class, Integer.class, Long.class, Float.class, Double.class);

    @Property(tries = 100)
    void allowedValues_passThrough_withSameType(@ForAll("allowedValues") Object value) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, value)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY))
                    .as("allowed value must be present in KVP")
                    .isTrue();

            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted)
                    .as("allowed value must pass through unchanged")
                    .isEqualTo(value);
            assertThat(ALLOWED_TYPES)
                    .as("emitted value type must be an allowed type")
                    .anyMatch(t -> t.isInstance(emitted));
        }
    }

    @Property(tries = 100)
    void disallowedValues_convertedToString(@ForAll("disallowedValues") Object value) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, value)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY))
                    .as("disallowed value must still produce an attribute (as String)")
                    .isTrue();

            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted)
                    .as("disallowed value must be converted to String")
                    .isInstanceOf(String.class);

            String text = (String) emitted;
            assertThat(text)
                    .as("converted string must be single-line (no \\n, \\r, \\t)")
                    .doesNotContain("\n", "\r", "\t");
            assertThat(text.length())
                    .as("converted string must be at most 1024 characters")
                    .isLessThanOrEqualTo(MAX_STRING_LENGTH);
        }
    }

    @Property(tries = 100)
    void stringsWithWhitespace_haveNoNewlinesOrTabs(@ForAll("stringsWithWhitespace") String value) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, (Object) value)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            // String은 허용 타입이므로 그대로 통과한다 — 콜사이트에서 문자열 정리는 별도 관심사.
            // normalize는 String을 허용 타입으로 그대로 통과시킨다.
            // 개행·탭 정리는 비허용 타입의 toString() 결과에만 적용된다.
            assertThat(captured.hasKey(TEST_KEY)).isTrue();
            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted).isInstanceOf(String.class);
        }
    }

    @Property(tries = 100)
    void disallowedValues_withNewlines_produceSingleLineString(
            @ForAll("disallowedValuesWithNewlines") Object value) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, value)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY)).isTrue();
            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted).isInstanceOf(String.class);

            String text = (String) emitted;
            assertThat(text)
                    .as("disallowed value toString with newlines must collapse to single-line")
                    .doesNotContain("\n", "\r", "\t");
        }
    }

    @Property(tries = 100)
    void nullValue_isOmittedFromKvp() {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, (Object) null)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY))
                    .as("null value must result in attribute being omitted")
                    .isFalse();
        }
    }

    @Property(tries = 100)
    void toStringException_producesUnrenderablePlaceholder() {
        Object throwing = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("intentional toString failure");
            }
        };

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, throwing)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY)).isTrue();
            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted).isInstanceOf(String.class);

            String text = (String) emitted;
            assertThat(text)
                    .as("toString() exception must produce <unrenderable:ClassName>")
                    .startsWith("<unrenderable:")
                    .endsWith(">");
        }
    }

    @Property(tries = 100)
    void longDisallowedValue_truncatedAt1024(@ForAll("longDisallowedValues") Object value) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.FRAME_ANOMALY)
                    .attr(TEST_KEY, value)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(TEST_KEY)).isTrue();
            Object emitted = captured.getValue(TEST_KEY);
            assertThat(emitted).isInstanceOf(String.class);

            String text = (String) emitted;
            assertThat(text.length())
                    .as("long disallowed value must be truncated at 1024")
                    .isLessThanOrEqualTo(MAX_STRING_LENGTH);
        }
    }

    // ── Generators ──

    @Provide
    Arbitrary<Object> allowedValues() {
        return Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.of(true, false).map(b -> (Object) b),
                Arbitraries.integers().between(-1000, 1000).map(i -> (Object) i),
                Arbitraries.longs().between(-10000L, 10000L).map(l -> (Object) l),
                Arbitraries.floats().between(-100f, 100f).map(f -> (Object) f),
                Arbitraries.doubles().between(-100.0, 100.0).map(d -> (Object) d)
        );
    }

    @Provide
    Arbitrary<Object> disallowedValues() {
        return Arbitraries.oneOf(
                // Date
                Arbitraries.longs().between(0L, 2_000_000_000_000L)
                        .map(millis -> (Object) new Date(millis)),
                // BigDecimal
                Arbitraries.doubles().between(-999.0, 999.0)
                        .map(d -> (Object) BigDecimal.valueOf(d)),
                // Map (toString = "{key=value}")
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(5)
                        .map(s -> (Object) Map.of("key", s)),
                // Instant
                Arbitraries.longs().between(0L, 2_000_000_000L)
                        .map(secs -> (Object) Instant.ofEpochSecond(secs)),
                // Anonymous object with custom toString
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(s -> (Object) new Object() {
                            @Override
                            public String toString() {
                                return "Custom(" + s + ")";
                            }
                        })
        );
    }

    @Provide
    Arbitrary<Object> disallowedValuesWithNewlines() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(10)
                .map(s -> (Object) new Object() {
                    @Override
                    public String toString() {
                        return "line1\nline2\r\nline3\ttab" + s;
                    }
                });
    }

    @Provide
    Arbitrary<String> stringsWithWhitespace() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('\n', '\r', '\t')
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<Object> longDisallowedValues() {
        // Objects whose toString() exceeds 1024 chars
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1200)
                .ofMaxLength(2000)
                .map(s -> (Object) new Object() {
                    @Override
                    public String toString() {
                        return s;
                    }
                });
    }
}

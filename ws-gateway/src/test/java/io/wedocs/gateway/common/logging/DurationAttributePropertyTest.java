package io.wedocs.gateway.common.logging;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 3.5, 4.4**
///
/// Property 6: 경과 시간 속성의 키 형식과 정수 값 —
/// 임의 Duration(nanos 단위)을 `durationMs()`로 emit하면 KVP 값이 `duration.toMillis()`와
/// 같은 Long 정수이고, 키가 `wedocs.<작업>.duration_ms` 형식을 만족하며,
/// Duration이 null이면 해당 키가 KVP에 존재하지 않음을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 6: 경과 시간 속성의 키 형식과 정수 값")
class DurationAttributePropertyTest {

    private static final Logger logger = LoggerFactory.getLogger(LogEvents.class);

    /// duration_ms 키 형식 정규식: `wedocs.<word>(.<word>)*.duration_ms`
    private static final Pattern DURATION_KEY_PATTERN =
            Pattern.compile("^wedocs\\.\\w+(\\.\\w+)*\\.duration_ms$");

    @Property(tries = 100)
    void durationKeys_matchExpectedFormat() {
        // LogFields에 정의된 duration_ms 키들이 규약 형식을 만족하는지 확인
        assertThat(DURATION_KEY_PATTERN.matcher(LogFields.HANDSHAKE_VERIFY_MS).matches())
                .as("HANDSHAKE_VERIFY_MS key '%s' must match duration_ms format",
                        LogFields.HANDSHAKE_VERIFY_MS)
                .isTrue();

        assertThat(DURATION_KEY_PATTERN.matcher(LogFields.HANDSHAKE_CHECK_PERMISSION_MS).matches())
                .as("HANDSHAKE_CHECK_PERMISSION_MS key '%s' must match duration_ms format",
                        LogFields.HANDSHAKE_CHECK_PERMISSION_MS)
                .isTrue();
    }

    @Property(tries = 100)
    void arbitraryDuration_emitsMillisLong(@ForAll("durations") Duration duration) {
        String durationKey = LogFields.HANDSHAKE_VERIFY_MS;

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_OK)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .attr(LogFields.USER_HASH, "test-hash")
                    .durationMs(durationKey, duration)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            // 값은 Long 타입이고 duration.toMillis()와 동치
            assertThat(captured.hasKey(durationKey))
                    .as("duration key must be present when duration is non-null")
                    .isTrue();
            assertThat(captured.getLong(durationKey))
                    .as("duration value must equal duration.toMillis()")
                    .isEqualTo(duration.toMillis());
        }
    }

    @Property(tries = 100)
    void zeroDuration_emitsZeroLong() {
        String durationKey = LogFields.HANDSHAKE_CHECK_PERMISSION_MS;

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_OK)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .attr(LogFields.USER_HASH, "test-hash")
                    .durationMs(durationKey, Duration.ZERO)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.getLong(durationKey))
                    .as("Duration.ZERO must emit 0L")
                    .isEqualTo(0L);
        }
    }

    @Property(tries = 100)
    void largeDuration_emitsCorrectMillis() {
        // Long.MAX_VALUE / 1_000_000 = max millis representable from nanos without overflow
        long maxSafeMillis = Long.MAX_VALUE / 1_000_000;
        Duration large = Duration.ofMillis(maxSafeMillis);
        String durationKey = LogFields.HANDSHAKE_VERIFY_MS;

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_OK)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .attr(LogFields.USER_HASH, "test-hash")
                    .durationMs(durationKey, large)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.getLong(durationKey))
                    .as("large duration must convert correctly to millis")
                    .isEqualTo(maxSafeMillis);
        }
    }

    @Property(tries = 100)
    void nullDuration_omitsAttribute() {
        String durationKey = LogFields.HANDSHAKE_VERIFY_MS;

        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_OK)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .attr(LogFields.USER_HASH, "test-hash")
                    .durationMs(durationKey, null)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.hasKey(durationKey))
                    .as("null duration must NOT emit the key")
                    .isFalse();
        }
    }

    @Provide
    Arbitrary<Duration> durations() {
        // Non-negative durations from 0 nanos to 10_000_000 ms (in nanos)
        return Arbitraries.longs()
                .between(0L, 10_000_000L * 1_000_000L)
                .map(Duration::ofNanos);
    }
}

package io.wedocs.gateway.common.logging;

import io.wedocs.gateway.auth.AuthMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("Feature: structured-logging-unification-v2")
class GatewayLogEventResultSetTest {

    @Test
    @DisplayName("핸드셰이크 판정 열거 집합 == AuthMetrics.RESULT_{OK,AUTHN_FAIL,AUTHZ_DENIED,BACKEND_ERROR}")
    void resultSet_matchesAuthMetricsConstants() {
        Set<String> actualResults = Arrays.stream(GatewayLogEvent.values())
                .map(GatewayLogEvent::result)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> expected = Set.of(
                AuthMetrics.RESULT_OK,
                AuthMetrics.RESULT_AUTHN_FAIL,
                AuthMetrics.RESULT_AUTHZ_DENIED,
                AuthMetrics.RESULT_BACKEND_ERROR
        );

        assertThat(actualResults).isEqualTo(expected);
    }

    @Test
    @DisplayName("RESULT_FAIL은 핸드셰이크 판정에 포함되지 않음 — jwt_verify 메트릭 전용")
    void resultFail_isExcludedFromHandshakeResults() {
        Set<String> actualResults = Arrays.stream(GatewayLogEvent.values())
                .map(GatewayLogEvent::result)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        assertThat(actualResults).doesNotContain(AuthMetrics.RESULT_FAIL);
    }
}

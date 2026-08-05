package io.wedocs.gateway.common.logging;

import io.wedocs.gateway.auth.AuthMetrics;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 4.2, 4.3, 6.2, 9.5, 10.2**
///
/// Property 7: 판정·오류 분류 열거값 —
/// ws-gateway taxonomy의 `wedocs.result` 값이 정확히
/// `{AuthMetrics.RESULT_OK, RESULT_AUTHN_FAIL, RESULT_AUTHZ_DENIED, RESULT_BACKEND_ERROR}`이고,
/// `GatewayErrorType.value()` 전수가 유효한 snake_case 문자열임을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 7: 판정·오류 분류 열거값")
class ResultAndErrorTypePropertyTest {

    /// snake_case: 소문자·숫자·밑줄, 밑줄로 시작/끝 금지, 연속 밑줄 금지.
    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

    /// 핸드셰이크 판정에 사용되는 result 닫힌 집합 (요구사항 6.2).
    /// RESULT_FAIL은 jwt_verify 메트릭 전용이므로 포함하지 않는다.
    private static final Set<String> EXPECTED_RESULT_SET = Set.of(
            AuthMetrics.RESULT_OK,
            AuthMetrics.RESULT_AUTHN_FAIL,
            AuthMetrics.RESULT_AUTHZ_DENIED,
            AuthMetrics.RESULT_BACKEND_ERROR
    );

    @Property(tries = 100)
    void allResultValues_matchExpectedHandshakeResultSet() {
        // taxonomy에 선언된 result() 값(non-null)이 정확히 expected set과 동일해야 한다.
        Set<String> actualResults = Arrays.stream(GatewayLogEvent.values())
                .map(GatewayLogEvent::result)
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actualResults)
                .as("wedocs.result values == {ok, authn_fail, authz_denied, backend_error}")
                .isEqualTo(EXPECTED_RESULT_SET);
    }

    @Property(tries = 100)
    void resultSetExcludesResultFail() {
        // RESULT_FAIL("fail")이 taxonomy result 값에 없음을 명시 확인.
        Set<String> actualResults = Arrays.stream(GatewayLogEvent.values())
                .map(GatewayLogEvent::result)
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actualResults)
                .as("RESULT_FAIL must not be in handshake result set")
                .doesNotContain(AuthMetrics.RESULT_FAIL);
    }

    @Property(tries = 100)
    void allGatewayErrorTypeValues_areValidSnakeCase() {
        // 모든 GatewayErrorType.value()가 유효한 snake_case 형식인지 확인.
        for (GatewayErrorType errorType : GatewayErrorType.values()) {
            assertThat(SNAKE_CASE.matcher(errorType.value()).matches())
                    .as("GatewayErrorType.%s value '%s' must be valid snake_case",
                            errorType.name(), errorType.value())
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void gatewayErrorTypeValueCount_matchesEnumEntryCount() {
        // value()가 모든 엔트리에서 고유한 값을 반환하는지 확인(중복 value 탐지).
        long distinctCount = Arrays.stream(GatewayErrorType.values())
                .map(GatewayErrorType::value)
                .distinct()
                .count();

        assertThat(distinctCount)
                .as("all GatewayErrorType entries must have unique values")
                .isEqualTo(GatewayErrorType.values().length);
    }

    @Property(tries = 100)
    void gatewayErrorTypeEnumeration_hasExpected14Entries() {
        // GatewayErrorType이 설계에서 선언한 14개 엔트리를 가지는지 확인.
        assertThat(GatewayErrorType.values())
                .as("GatewayErrorType must have exactly 14 entries")
                .hasSize(14);
    }

    @Property(tries = 100)
    void allGatewayErrorTypeValues_areNotEmpty() {
        // 모든 value()가 빈 문자열이 아님을 확인.
        for (GatewayErrorType errorType : GatewayErrorType.values()) {
            assertThat(errorType.value())
                    .as("GatewayErrorType.%s value must not be empty", errorType.name())
                    .isNotEmpty();
        }
    }
}

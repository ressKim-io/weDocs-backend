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

    /// `GatewayErrorType` 엔트리 수 — 갱신은 대시보드·알림 계약 변경을 뜻한다.
    /// 2026-08-07 M3 Phase 1: 14 → 16 (`send_limit_exceeded` 데코레이터 송신 상한 초과,
    /// `awareness_too_large` awareness fan-out 증폭 차단).
    private static final int EXPECTED_ERROR_TYPE_ENTRIES = 16;

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
    void gatewayErrorTypeEnumeration_isAClosedSet() {
        // `error.type`은 닫힌 집합이다 — 엔트리 추가는 대시보드·알림 계약 변경이므로 의도적 편집을
        // 강제한다. 기대값은 이 상수 하나만 고치면 되도록 메서드명에서 분리했다(과거엔 이름에 숫자가
        // 박혀 있어 엔트리를 늘릴 때마다 메서드명까지 stale이 됐다).
        assertThat(GatewayErrorType.values())
                .as("GatewayErrorType 엔트리 수는 의도적으로만 바뀐다")
                .hasSize(EXPECTED_ERROR_TYPE_ENTRIES);
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

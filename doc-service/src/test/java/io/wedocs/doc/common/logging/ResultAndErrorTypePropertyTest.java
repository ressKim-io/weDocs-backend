package io.wedocs.doc.common.logging;

import io.wedocs.doc.common.error.DocErrorCode;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 4.2, 4.3, 6.2, 9.5, 10.2**
///
/// Property 7: 판정·오류 분류 열거값 —
/// doc-service taxonomy의 `wedocs.result` 값이 닫힌 판정 집합에 속하고,
/// `error.type` 값이 선언된 오류 분류 집합에 속하며,
/// `DocErrorCode.slug()` 전수가 유효한 kebab-case 문자열이고
/// 전체 error.type 허용 집합 = `DocErrorCode.slug()` ∪ `DocLogErrorType.value()`임을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 7: 판정·오류 분류 열거값")
class ResultAndErrorTypePropertyTest {

    /// kebab-case: 소문자·숫자·하이픈, 하이픈으로 시작/끝 금지, 연속 하이픈 금지.
    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    /// doc-service taxonomy의 닫힌 판정 열거 집합. null은 "판정 없음"이므로 제외.
    private static final Set<String> EXPECTED_RESULT_SET = Set.of("rejected", "failed", "ok");

    /// `DocErrorCode.slug()` 전수.
    private static final Set<String> DOC_ERROR_CODE_SLUGS = Arrays.stream(DocErrorCode.values())
            .map(DocErrorCode::slug)
            .collect(Collectors.toUnmodifiableSet());

    /// `DocLogErrorType.value()` 전수.
    private static final Set<String> DOC_LOG_ERROR_TYPE_VALUES = Arrays.stream(DocLogErrorType.values())
            .map(DocLogErrorType::value)
            .collect(Collectors.toUnmodifiableSet());

    /// error.type 전체 허용 집합 = DocErrorCode.slug() ∪ DocLogErrorType.value().
    private static final Set<String> ALL_ERROR_TYPE_VALUES = Stream.concat(
            DOC_ERROR_CODE_SLUGS.stream(),
            DOC_LOG_ERROR_TYPE_VALUES.stream()
    ).collect(Collectors.toUnmodifiableSet());

    @Property(tries = 100)
    void allResultValues_belongToClosedResultSet() {
        // taxonomy에 선언된 result() 값(non-null)이 정확히 expected set에 속한다.
        Set<String> actualResults = Arrays.stream(DocLogEvent.values())
                .map(DocLogEvent::result)
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actualResults)
                .as("taxonomy result values must be a subset of the closed set")
                .isSubsetOf(EXPECTED_RESULT_SET);

        assertThat(EXPECTED_RESULT_SET)
                .as("expected result set must match actual taxonomy results exactly")
                .isEqualTo(actualResults);
    }

    @Property(tries = 100)
    void allDocErrorCodeSlugs_areValidKebabCase() {
        // 모든 DocErrorCode.slug()가 유효한 kebab-case 형식인지 확인.
        for (DocErrorCode code : DocErrorCode.values()) {
            assertThat(KEBAB_CASE.matcher(code.slug()).matches())
                    .as("DocErrorCode.%s slug '%s' must be valid kebab-case",
                            code.name(), code.slug())
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void allDocLogErrorTypeValues_areValidKebabCase() {
        // 모든 DocLogErrorType.value()가 유효한 kebab-case 형식인지 확인.
        for (DocLogErrorType errorType : DocLogErrorType.values()) {
            assertThat(KEBAB_CASE.matcher(errorType.value()).matches())
                    .as("DocLogErrorType.%s value '%s' must be valid kebab-case",
                            errorType.name(), errorType.value())
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void combinedErrorTypeSet_equalsDocErrorCodeSlugsUnionDocLogErrorTypeValues() {
        // error.type 전체 허용 집합이 정확히 두 열거형의 합집합인지 확인.
        Set<String> union = Stream.concat(
                Arrays.stream(DocErrorCode.values()).map(DocErrorCode::slug),
                Arrays.stream(DocLogErrorType.values()).map(DocLogErrorType::value)
        ).collect(Collectors.toUnmodifiableSet());

        assertThat(ALL_ERROR_TYPE_VALUES).isEqualTo(union);
    }

    @Property(tries = 100)
    void docErrorCodeSlugs_andDocLogErrorTypeValues_areDisjoint() {
        // 두 집합에 중복이 없어야 한다 — 같은 문자열이 양쪽에 정의되면 카탈로그 드리프트.
        Set<String> intersection = DOC_ERROR_CODE_SLUGS.stream()
                .filter(DOC_LOG_ERROR_TYPE_VALUES::contains)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(intersection)
                .as("DocErrorCode slugs and DocLogErrorType values must be disjoint")
                .isEmpty();
    }

    @Property(tries = 100)
    void docErrorCodeSlugCount_matchesEnumEntryCount() {
        // slug()가 모든 엔트리에서 고유한 값을 반환하는지 확인(중복 slug 탐지).
        long distinctCount = Arrays.stream(DocErrorCode.values())
                .map(DocErrorCode::slug)
                .distinct()
                .count();

        assertThat(distinctCount)
                .as("all DocErrorCode entries must have unique slugs")
                .isEqualTo(DocErrorCode.values().length);
    }
}

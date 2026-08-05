package io.wedocs.gateway.common.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 3.1, 3.4, 3.7, 9.6**
///
/// Property 4: Attribute_Key 표기·네임스페이스 규약 —
/// LogFields에 선언된 모든 Attribute_Key 상수가 소문자·점 네임스페이스 표기
/// (`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`)를 만족하고, 등록된 Semantic Convention 속성이거나
/// `wedocs.` 네임스페이스에 속하며, 이 규칙의 예외 집합이 정확히 `{trace_id, span_id}`임을 확인한다.
/// 임의 생성된 `wedocs.*` 키도 regex를 통과함을 property로 검증한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 4: Attribute_Key 표기·네임스페이스 규약")
class AttributeKeyConventionPropertyTest {

    /// 소문자·점 네임스페이스 표기 규칙. 한 구성요소 안의 단어 구분만 밑줄 허용.
    private static final Pattern DOT_NOTATION =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    /// 점 표기 규칙의 명시적 예외 — OTLP LogRecord 최상위 필드 이름이라 점이 없다.
    private static final Set<String> EXCEPTION_SET = Set.of("trace_id", "span_id");

    /// OTel Semantic Convention에 등록된 속성 키 집합.
    private static final Set<String> SEMCONV_KEYS = Set.of(
            "user.hash", "error.type", "rpc.method", "rpc.service", "server.port", "event.name");

    /// LogFields의 `public static final String` 필드 값 전수.
    private static final List<String> ALL_KEY_CONSTANTS = extractStringConstants();

    /// 예외 집합에 속하지 않는 일반 키 상수.
    private static final List<String> NORMAL_KEYS = ALL_KEY_CONSTANTS.stream()
            .filter(key -> !EXCEPTION_SET.contains(key))
            .toList();

    /// 예외 집합에 속하는 키 상수.
    private static final List<String> EXCEPTION_KEYS = ALL_KEY_CONSTANTS.stream()
            .filter(EXCEPTION_SET::contains)
            .toList();

    @Property(tries = 100)
    void allConstants_matchDotNotationRegex() {
        // 예외 집합 포함 전체 상수가 regex를 만족한다 — trace_id/span_id도 단일 세그먼트라 통과.
        // 예외의 근거는 regex 위반이 아니라 네임스페이스 소속이다.
        for (String key : ALL_KEY_CONSTANTS) {
            assertThat(DOT_NOTATION.matcher(key).matches())
                    .as("key '%s' must match dot notation regex", key)
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void allNormalConstants_belongToSemconvOrWedocsNamespace() {
        for (String key : NORMAL_KEYS) {
            boolean isSemconv = SEMCONV_KEYS.contains(key);
            boolean isWedocs = key.startsWith("wedocs.");
            assertThat(isSemconv || isWedocs)
                    .as("key '%s' must be semconv or wedocs.* namespace", key)
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void exceptionSet_isExactlyTraceIdAndSpanId() {
        // 네임스페이스 규칙(semconv 또는 wedocs.*)을 만족하지 않는 키가 정확히 {trace_id, span_id}임을 확인
        Set<String> actualExceptions = ALL_KEY_CONSTANTS.stream()
                .filter(key -> !SEMCONV_KEYS.contains(key) && !key.startsWith("wedocs."))
                .collect(Collectors.toSet());
        assertThat(actualExceptions).isEqualTo(EXCEPTION_SET);
    }

    @Property(tries = 100)
    void exceptionKeys_areInterimAttributes() {
        // trace_id, span_id는 regex는 통과하지만 semconv/wedocs 네임스페이스에 속하지 않는다.
        // 이것이 Interim_Attribute(OTLP 도입 시 제거)로 분류되는 근거다.
        for (String key : EXCEPTION_KEYS) {
            assertThat(DOT_NOTATION.matcher(key).matches())
                    .as("exception key '%s' must still match regex", key)
                    .isTrue();
            assertThat(SEMCONV_KEYS.contains(key) || key.startsWith("wedocs."))
                    .as("exception key '%s' must NOT be in semconv/wedocs namespace", key)
                    .isFalse();
        }
    }

    @Property(tries = 100)
    void arbitraryWedocsKeys_matchDotNotationRegex(@ForAll("wedocsKeys") String key) {
        assertThat(DOT_NOTATION.matcher(key).matches())
                .as("generated wedocs key '%s' must match dot notation regex", key)
                .isTrue();
    }

    @Provide
    Arbitrary<String> wedocsKeys() {
        // wedocs.<segment>(.<segment>)* 형식의 임의 키 생성
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('_')
                .ofMinLength(1)
                .ofMaxLength(12)
                .filter(s -> s.matches("[a-z][a-z0-9_]*"));

        return segment.list()
                .ofMinSize(1)
                .ofMaxSize(3)
                .map(segments -> "wedocs." + String.join(".", segments));
    }

    private static List<String> extractStringConstants() {
        return Arrays.stream(LogFields.class.getDeclaredFields())
                .filter(AttributeKeyConventionPropertyTest::isPublicStaticFinalString)
                .map(AttributeKeyConventionPropertyTest::getFieldValue)
                .filter(value -> !value.equals(LogFields.NONE)) // NONE="-"은 키가 아니라 placeholder
                .toList();
    }

    private static boolean isPublicStaticFinalString(Field field) {
        int mods = field.getModifiers();
        return Modifier.isPublic(mods)
                && Modifier.isStatic(mods)
                && Modifier.isFinal(mods)
                && field.getType() == String.class;
    }

    private static String getFieldValue(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field: " + field.getName(), e);
        }
    }
}

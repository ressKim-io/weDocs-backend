package io.wedocs.gateway.architecture;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property 2: 위반 메시지 템플릿의 포맷 호환성.
 *
 * <p>임의의 문자열 인자를 각 ArchRuleConstants 템플릿에 {@code String.format}으로
 * 전달할 때 예외가 발생하지 않고, 결과 문자열에 전달된 인자가 포함됨을 검증한다.
 *
 * <p><b>Validates: Requirements 3.3</b>
 */
@Tag("Feature: error-message-centralization")
@Tag("Property 2: 템플릿 포맷 호환성")
class ArchRuleConstantsPropertyTest {

    @Property(tries = 100)
    void valueAnnotationViolation_format_does_not_throw_and_contains_args(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String fieldName,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String className) {

        assertThatCode(() -> String.format(ArchRuleConstants.VALUE_ANNOTATION_VIOLATION, fieldName, className))
                .doesNotThrowAnyException();

        String result = String.format(ArchRuleConstants.VALUE_ANNOTATION_VIOLATION, fieldName, className);
        assertThat(result).contains(fieldName);
        assertThat(result).contains(className);
    }

    @Property(tries = 100)
    void fieldNotFinalViolation_format_does_not_throw_and_contains_args(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String fieldName,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String className) {

        assertThatCode(() -> String.format(ArchRuleConstants.FIELD_NOT_FINAL_VIOLATION, fieldName, className))
                .doesNotThrowAnyException();

        String result = String.format(ArchRuleConstants.FIELD_NOT_FINAL_VIOLATION, fieldName, className);
        assertThat(result).contains(fieldName);
        assertThat(result).contains(className);
    }
}

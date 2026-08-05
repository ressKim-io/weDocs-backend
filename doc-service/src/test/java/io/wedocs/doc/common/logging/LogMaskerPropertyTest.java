package io.wedocs.doc.common.logging;

import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.NotBlank;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 7.1, 7.2, 7.3, 7.9, 7.10**
///
/// Property 10: 마스킹 일관성과 비가역성 —
/// 두 모듈의 LogMasker가 동일한 고정 벡터 집합에 대해 동일 출력을 산출하고,
/// 비어 있지 않은 입력에 대해 출력은 10 hex 문자이며 원문과 다르고,
/// 빈 입력에 대해 placeholder `-`을 반환하며, 결정론적이고 약한 충돌 저항성을 갖춘다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 10: 마스킹 일관성과 비가역성")
class LogMaskerPropertyTest {

    /// SHA-256 앞 5바이트 hex로 사전 계산된 고정 벡터.
    /// 두 모듈이 이 벡터를 공유해 사본 간 출력 동치를 고정한다.
    private static final Map<String, String> FIXED_VECTORS = Map.of(
            "user-123", "fcdec6df4d",
            "alice@example.com", "ff8d9819fc",
            "550e8400-e29b-41d4-a716-446655440000", "a3a9e1ed97",
            "한글사용자", "4e3919df74",
            "a", "ca978112ca"
    );

    @Property(tries = 1)
    void fixedVectorConsistency_allPrecomputedPairsMatch() {
        FIXED_VECTORS.forEach((input, expected) ->
                assertThat(LogMasker.mask(input))
                        .as("mask('%s') must equal pre-computed vector", input)
                        .isEqualTo(expected));
    }

    @Property(tries = 200)
    void outputFormat_nonEmptyInputProduces10HexChars(@ForAll("nonBlankStrings") String input) {
        String masked = LogMasker.mask(input);
        assertThat(masked).matches("^[a-f0-9]{10}$");
    }

    @Property(tries = 200)
    void irreversibility_outputDiffersFromInput(@ForAll("longStrings") String input) {
        String masked = LogMasker.mask(input);
        assertThat(masked).isNotEqualTo(input);
    }

    @Property(tries = 1)
    void nullAndBlankHandling_returnsPlaceholder() {
        assertThat(LogMasker.mask(null)).isEqualTo(LogFields.NONE);
        assertThat(LogMasker.mask("")).isEqualTo(LogFields.NONE);
        assertThat(LogMasker.mask("   ")).isEqualTo(LogFields.NONE);
        assertThat(LogMasker.mask("\t\n")).isEqualTo(LogFields.NONE);
    }

    @Property(tries = 200)
    void determinism_sameMaskForSameInput(@ForAll("nonBlankStrings") String input) {
        String first = LogMasker.mask(input);
        String second = LogMasker.mask(input);
        assertThat(first).isEqualTo(second);
    }

    @Property(tries = 200)
    void weakCollisionResistance_differentInputsProduceDifferentOutputs(
            @ForAll("nonBlankStrings") String a,
            @ForAll("nonBlankStrings") String b) {
        if (a.equals(b)) {
            return; // skip identical pairs
        }
        assertThat(LogMasker.mask(a)).isNotEqualTo(LogMasker.mask(b));
    }

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(100)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<String> longStrings() {
        // 길이 > 10인 문자열로 제한해 출력(10자)과 입력이 길이만으로도 다를 수 있게 한다
        return Arbitraries.strings()
                .ofMinLength(11)
                .ofMaxLength(100)
                .filter(s -> !s.isBlank());
    }
}

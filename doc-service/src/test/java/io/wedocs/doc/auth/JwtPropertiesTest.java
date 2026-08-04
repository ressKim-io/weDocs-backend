package io.wedocs.doc.auth;

import io.wedocs.doc.common.error.InfraErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    @DisplayName("음수 TTL → IllegalArgumentException 메시지가 InfraErrorCode와 일치한다")
    void negativeTtl_throwsWithEnumMessage() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofHours(-1), "wedocs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InfraErrorCode.JWT_TTL_MUST_BE_POSITIVE.message());
    }

    @Test
    @DisplayName("0 TTL → IllegalArgumentException 메시지가 InfraErrorCode와 일치한다")
    void zeroTtl_throwsWithEnumMessage() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ZERO, "wedocs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InfraErrorCode.JWT_TTL_MUST_BE_POSITIVE.message());
    }

    @Test
    @DisplayName("빈 issuer → IllegalArgumentException 메시지가 InfraErrorCode와 일치한다")
    void emptyIssuer_throwsWithEnumMessage() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofHours(1), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InfraErrorCode.JWT_ISSUER_MUST_NOT_BE_BLANK.message());
    }

    @Test
    @DisplayName("공백만 있는 issuer → IllegalArgumentException 메시지가 InfraErrorCode와 일치한다")
    void blankIssuer_throwsWithEnumMessage() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofHours(1), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InfraErrorCode.JWT_ISSUER_MUST_NOT_BE_BLANK.message());
    }
}

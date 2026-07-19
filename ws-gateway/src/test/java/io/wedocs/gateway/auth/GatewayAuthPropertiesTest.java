package io.wedocs.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 설정 fail-closed 회귀 가드 (config-contract-audit). 컴팩트 생성자의 검증 분기가 느슨해지거나 삭제되면
/// 이 테스트가 실패한다 — 빈 검증키 소스·빈 발급자로 조용히 기동하는 것을 기동 시점에 막는 계약을 고정한다.
class GatewayAuthPropertiesTest {

    private static final String URI = "http://localhost:8081/.well-known/jwks.json";
    private static final Duration SKEW = Duration.ofSeconds(60);

    @Test
    @DisplayName("jwks-uri가 blank면 기동 실패")
    void blankJwksUri_throws() {
        assertThatThrownBy(() -> new GatewayAuthProperties("  ", "wedocs-doc-service", "wedocs.sync.v1", SKEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("issuer가 blank면 기동 실패")
    void blankIssuer_throws() {
        assertThatThrownBy(() -> new GatewayAuthProperties(URI, "  ", "wedocs.sync.v1", SKEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("subprotocol이 blank면 기동 실패")
    void blankSubprotocol_throws() {
        assertThatThrownBy(() -> new GatewayAuthProperties(URI, "wedocs-doc-service", "  ", SKEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clock-skew가 음수면 기동 실패")
    void negativeClockSkew_throws() {
        assertThatThrownBy(() -> new GatewayAuthProperties(URI, "wedocs-doc-service", "wedocs.sync.v1", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("유효 값은 통과")
    void validProperties_ok() {
        assertThatCode(() -> new GatewayAuthProperties(URI, "wedocs-doc-service", "wedocs.sync.v1", SKEW))
                .doesNotThrowAnyException();
    }
}

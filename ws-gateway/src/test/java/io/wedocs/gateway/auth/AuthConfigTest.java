package io.wedocs.gateway.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// JWKS URI fail-fast 회귀 가드 (config-contract-audit). 잘못된 jwks-uri로 검증기 빈을 만들면 런타임(첫 핸드셰이크)이
/// 아니라 기동 시점에 즉시 실패해야 한다 — lazy fetch 특성상 조용히 기동하면 실패가 사용자 트래픽에서야 드러난다.
class AuthConfigTest {

    @Test
    @DisplayName("잘못된 jwks-uri는 검증기 생성 시 즉시 IllegalStateException")
    void malformedJwksUri_failsFast() {
        AuthConfig config = new AuthConfig();
        GatewayAuthProperties props = new GatewayAuthProperties(
                "http://exa mple.com/jwks", "wedocs-doc-service", "wedocs.sync.v1", Duration.ofSeconds(60));

        assertThatThrownBy(() -> config.jwtVerifier(props, new AuthMetrics(new SimpleMeterRegistry())))
                .isInstanceOf(IllegalStateException.class);
    }
}

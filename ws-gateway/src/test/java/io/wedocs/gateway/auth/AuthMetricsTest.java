package io.wedocs.gateway.auth;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// 관측 계약 회귀 가드 (ADR-0021). Micrometer base name(dot)이 Prometheus에서 underscore + `_total`로 렌더링돼
/// 계약 이름과 정확히 일치하는지 실제 스크레이프로 고정한다 — 이름이 바뀌면 대시보드·알림이 조용히 깨지므로.
class AuthMetricsTest {

    @Test
    @DisplayName("세 카운터가 계약 이름(ws_handshake_total·jwt_verify_total·jwks_refresh_total)으로 노출된다")
    void metrics_renderWithContractNamesAndResultTag() {
        // Given: Prometheus 레지스트리에 배선된 AuthMetrics
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AuthMetrics metrics = new AuthMetrics(registry);

        // When: 각 결과 경로를 1회씩 발화
        metrics.handshake(AuthMetrics.RESULT_OK);
        metrics.handshake(AuthMetrics.RESULT_AUTHN_FAIL);
        metrics.jwtVerify(AuthMetrics.RESULT_OK);
        metrics.jwtVerify(AuthMetrics.RESULT_FAIL);
        metrics.jwksRefresh(AuthMetrics.RESULT_OK);
        metrics.jwksRefresh(AuthMetrics.RESULT_FAIL);
        String scrape = registry.scrape();

        // Then: 계약 이름 + result 태그로 렌더링(Prometheus counter 접미 _total 검증)
        assertThat(scrape)
                .contains("ws_handshake_total{result=\"ok\"}")
                .contains("ws_handshake_total{result=\"authn_fail\"}")
                .contains("jwt_verify_total{result=\"ok\"}")
                .contains("jwt_verify_total{result=\"fail\"}")
                .contains("jwks_refresh_total{result=\"ok\"}")
                .contains("jwks_refresh_total{result=\"fail\"}");
    }
}

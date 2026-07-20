package io.wedocs.gateway.auth;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    @Test
    @DisplayName("인가 메트릭이 계약 이름으로 노출된다(authz_denied·backend_error·checkpermission_duration·authz_backend_error_total)")
    void authzMetrics_renderWithContractNames() {
        // Given
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AuthMetrics metrics = new AuthMetrics(registry);

        // When: 인가 슬라이스(2a-2)가 추가한 결과 경로를 발화
        metrics.handshake(AuthMetrics.RESULT_AUTHZ_DENIED);
        metrics.handshake(AuthMetrics.RESULT_BACKEND_ERROR);
        metrics.checkPermission(Duration.ofMillis(12));
        metrics.authzBackendError();
        String scrape = registry.scrape();

        // Then: 2a-1이 세운 ws_handshake_total 계약을 태그값으로 연장하고, 신규 계기는 계약 이름 그대로.
        // Timer는 Prometheus에서 초 단위 `_seconds` 접미로 렌더링된다 — 대시보드 쿼리가 이 이름에 걸린다.
        assertThat(scrape)
                .contains("ws_handshake_total{result=\"authz_denied\"}")
                .contains("ws_handshake_total{result=\"backend_error\"}")
                .contains("checkpermission_duration_seconds_count")
                .contains("authz_backend_error_total");
    }
}

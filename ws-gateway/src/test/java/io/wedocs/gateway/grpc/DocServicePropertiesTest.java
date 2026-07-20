package io.wedocs.gateway.grpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// 인가 백엔드 설정의 fail-fast 검증 (config-contract-audit). 오설정이 기동 시가 아니라 런타임 403 폭주로
/// 드러나면 원인 추적이 훨씬 비싸므로, 잘못된 값이 실제로 기동을 막는지 고정한다.
class DocServicePropertiesTest {

    private static final String TARGET = "localhost:50052";

    @Test
    @DisplayName("정상 값은 그대로 바인딩된다")
    void validValues_areAccepted() {
        DocServiceProperties properties = new DocServiceProperties(TARGET, Duration.ofSeconds(2));

        assertThat(properties.target()).isEqualTo(TARGET);
        assertThat(properties.checkPermissionTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("target이 비면 기동에 실패한다 — 인가 백엔드가 없으면 전 연결이 거절된다")
    void blankTarget_failsFast() {
        assertThatThrownBy(() -> new DocServiceProperties("  ", Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wedocs.doc-service.target");
    }

    @Test
    @DisplayName("timeout이 0이면 기동에 실패한다 — 모든 호출이 즉시 DEADLINE_EXCEEDED가 된다")
    void zeroTimeout_failsFast() {
        assertThatThrownBy(() -> new DocServiceProperties(TARGET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-permission-timeout");
    }

    @Test
    @DisplayName("timeout이 음수여도 같은 이유로 기동에 실패한다")
    void negativeTimeout_failsFast() {
        assertThatThrownBy(() -> new DocServiceProperties(TARGET, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

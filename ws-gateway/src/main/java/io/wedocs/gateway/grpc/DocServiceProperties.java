package io.wedocs.gateway.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/// WS 핸드셰이크 인가에 쓰는 doc-service gRPC 설정 (ADR-0014 §인가=doc-service 권위).
/// target은 명시 필수 — 빈 값이면 인가 백엔드가 없어 fail-closed로 **전 연결이 거절**되는데, 그 사실이
/// 런타임 403 폭주로만 드러난다. 기동 시 즉시 실패시켜 오설정을 배포 전에 잡는다(config-contract-audit).
@ConfigurationProperties("wedocs.doc-service")
public record DocServiceProperties(
        String target,
        @DefaultValue("2s") Duration checkPermissionTimeout) {

    public DocServiceProperties {
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("wedocs.doc-service.target must not be blank");
        }
        // 0/음수 deadline은 grpc-java에서 즉시 만료 → 모든 핸드셰이크가 DEADLINE_EXCEEDED로 거절된다.
        if (checkPermissionTimeout.isZero() || checkPermissionTimeout.isNegative()) {
            throw new IllegalArgumentException("wedocs.doc-service.check-permission-timeout must be positive");
        }
    }
}

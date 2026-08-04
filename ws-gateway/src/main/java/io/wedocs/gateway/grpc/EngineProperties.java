package io.wedocs.gateway.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/// crdt-engine gRPC 접속 설정 (bidi Sync). target은 명시 필수 — 빈 값이면
/// CRDT 동기화 불가이므로 기동 시 즉시 실패(config-contract-audit).
@ConfigurationProperties("wedocs.engine")
public record EngineProperties(
        @DefaultValue("localhost:50051") String target) {

    public EngineProperties {
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("wedocs.engine.target must not be blank");
        }
    }
}

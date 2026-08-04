package io.wedocs.doc.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/// doc-service 내부 gRPC 서버 설정 (M2 1b). @Value 산포 대신 타입-안전 바인딩.
/// 값은 application.yml `wedocs.doc-service.grpc.*`에서 바인딩된다.
@ConfigurationProperties("wedocs.doc-service.grpc")
public record GrpcServerProperties(
        @DefaultValue("50052") int port,
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4194304") int maxInboundMessageSize) {

    public GrpcServerProperties {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("wedocs.doc-service.grpc.port must be 0–65535");
        }
        if (maxInboundMessageSize <= 0) {
            throw new IllegalArgumentException(
                    "wedocs.doc-service.grpc.max-inbound-message-size must be positive");
        }
    }
}

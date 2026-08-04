package io.wedocs.gateway.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/// WS Origin 화이트리스트 설정(secure-coding.md P5) — `*` 금지, 화이트리스트만.
/// 기본 = vite dev(5173). prod Origin은 WEDOCS_GATEWAY_ALLOWED_ORIGINS로 주입(환경 분리).
@ConfigurationProperties("wedocs.gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:5173") String[] allowedOrigins) {

    public GatewayProperties {
        if (allowedOrigins == null || allowedOrigins.length == 0) {
            throw new IllegalArgumentException(
                    "wedocs.gateway.allowed-origins must contain at least one origin");
        }
    }
}

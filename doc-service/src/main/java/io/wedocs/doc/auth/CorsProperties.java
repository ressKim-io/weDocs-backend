package io.wedocs.doc.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/// CORS 화이트리스트 설정 — `*` 금지(secure-coding P5). 기본 = vite dev(5173), prod는 env 주입.
@ConfigurationProperties("wedocs.doc-service.cors")
public record CorsProperties(
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "wedocs.doc-service.cors.allowed-origins must contain at least one origin");
        }
    }
}

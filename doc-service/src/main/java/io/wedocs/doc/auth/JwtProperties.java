package io.wedocs.doc.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/// JWT 발급 설정 (ADR-0017). privateKeyLocation은 Spring Resource 위치 문자열
/// (`file:` = K8s Secret 마운트, `classpath:` = 테스트) — 빈 값이면 dev 전용 임시 키(JwtKeys).
/// Resource 타입 직접 바인딩 대신 String을 쓰는 이유: 빈 문자열("" = env 미주입)을
/// "미설정"으로 명시 해석하기 위해 (config-contract-audit — 바인딩 암묵 동작 의존 금지).
@ConfigurationProperties("wedocs.doc-service.jwt")
public record JwtProperties(
        @DefaultValue("") String privateKeyLocation,
        @DefaultValue("24h") Duration ttl,   // ADR-0014: 예상 편집 세션보다 긴 TTL
        @DefaultValue("wedocs-doc-service") String issuer) {

    public JwtProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("jwt ttl must be positive");
        }
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalArgumentException("jwt issuer must not be blank");
        }
    }

    public boolean hasPrivateKeyLocation() {
        return StringUtils.hasText(privateKeyLocation);
    }
}

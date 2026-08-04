package io.wedocs.gateway.auth;

import io.wedocs.gateway.common.InfraErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/// WS 핸드셰이크 JWT 검증 설정 (ADR-0014 검증=gateway · ADR-0021 관측 · ADR-0017 RS256/JWKS).
/// jwks-uri는 명시 필수 — 빈 값이면 검증키 소스가 없어 전 연결이 인증 불가이므로 기동 시 즉시 실패
/// (config-contract-audit: 암묵 기본값 의존 금지, fail-closed).
@ConfigurationProperties("wedocs.gateway.auth")
public record GatewayAuthProperties(
        String jwksUri,
        @DefaultValue("wedocs-doc-service") String issuer,
        @DefaultValue("wedocs.sync.v1") String subprotocol,
        @DefaultValue("60s") Duration clockSkew) {

    public GatewayAuthProperties {
        if (!StringUtils.hasText(jwksUri)) {
            throw new IllegalArgumentException(InfraErrorCode.JWKS_URI_MUST_NOT_BE_BLANK.message());
        }
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalArgumentException(InfraErrorCode.ISSUER_MUST_NOT_BE_BLANK.message());
        }
        if (!StringUtils.hasText(subprotocol)) {
            throw new IllegalArgumentException(InfraErrorCode.SUBPROTOCOL_MUST_NOT_BE_BLANK.message());
        }
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException(InfraErrorCode.CLOCK_SKEW_MUST_NOT_BE_NEGATIVE.message());
        }
    }
}

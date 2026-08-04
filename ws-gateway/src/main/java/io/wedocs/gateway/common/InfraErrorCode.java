package io.wedocs.gateway.common;

/// ws-gateway 인프라 계층 에러 카탈로그 — ConfigurationProperties 검증 실패 등.
public enum InfraErrorCode {

    /// jwks-uri가 빈 문자열.
    JWKS_URI_MUST_NOT_BE_BLANK("wedocs.gateway.auth.jwks-uri must not be blank"),

    /// issuer가 빈 문자열.
    ISSUER_MUST_NOT_BE_BLANK("wedocs.gateway.auth.issuer must not be blank"),

    /// subprotocol이 빈 문자열.
    SUBPROTOCOL_MUST_NOT_BE_BLANK("wedocs.gateway.auth.subprotocol must not be blank"),

    /// clock-skew가 음수.
    CLOCK_SKEW_MUST_NOT_BE_NEGATIVE("wedocs.gateway.auth.clock-skew must not be negative");

    private final String message;

    InfraErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

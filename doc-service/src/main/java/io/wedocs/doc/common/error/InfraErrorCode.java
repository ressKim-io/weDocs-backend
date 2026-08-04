package io.wedocs.doc.common.error;

/// doc-service 인프라 계층 에러 카탈로그 — ConfigurationProperties 검증, 키 로딩 실패 등.
/// DocErrorCode(도메인 에러)와 보완 관계: DocErrorCode는 비즈니스 실패, 이 enum은 인프라 실패.
public enum InfraErrorCode {

    // ── JWT / 인증 ──

    /// JwtProperties: TTL이 0 이하.
    JWT_TTL_MUST_BE_POSITIVE("jwt ttl must be positive"),

    /// JwtProperties: issuer가 빈 문자열.
    JWT_ISSUER_MUST_NOT_BE_BLANK("jwt issuer must not be blank"),

    /// JwtKeys: PEM 파일 로드 실패. String.format 패턴 — %s = Resource.getDescription().
    JWT_KEY_LOAD_FAILED(
            "jwt private key load failed (expected PKCS#8 PEM: '-----BEGIN PRIVATE KEY-----'): %s"),

    /// JwtKeys: 공개키 변환 실패.
    JWT_PUBLIC_KEY_CONVERSION_FAILED("jwt public key conversion failed"),

    /// JwtKeys: RSA 키 생성 불가 (JRE에 RSA 미지원 — 사실상 발생 불가).
    RSA_KEY_GENERATION_UNAVAILABLE("RSA key generation unavailable"),

    /// JwtKeys: kid(thumbprint) 계산 실패.
    JWT_KID_COMPUTATION_FAILED("jwt kid(thumbprint) computation failed");

    private final String message;

    InfraErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /// String.format 패턴을 포함하는 엔트리용 — 컨텍스트 변수를 삽입하여 최종 메시지 생성.
    public String format(Object... args) {
        return String.format(message, args);
    }
}

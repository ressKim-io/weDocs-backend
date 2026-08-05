package io.wedocs.doc.common.logging;

/// doc-service `error.type` 속성에 쓰이는 오류 분류 중 **도메인 카탈로그 밖**의 값.
///
/// 도메인 에러(`page-not-found`, `workspace-not-found`, `snapshot-conflict` 등)는
/// `DocErrorCode.slug()`를 그대로 참조한다 — 동일 문자열을 여기에 새로 정의하면
/// 카탈로그 확장 시 두 곳을 동기화해야 하고 드리프트가 생긴다(요구사항 10.2).
///
/// 이 enum은 도메인 예외가 아닌 인프라/프로토콜 수준 실패만 담는다:
/// - `malformed-id`: UUID 파싱 실패(잘못된 형식의 요청 식별자)
/// - `unexpected-internal-error`: 예상치 못한 내부 오류(도메인 코드가 분류하지 않는 예외)
/// - `jwt-key-not-configured`: JWT 서명 키 설정 누락으로 임시 키를 생성한 경우
public enum DocLogErrorType {

    MALFORMED_ID("malformed-id"),
    UNEXPECTED_INTERNAL_ERROR("unexpected-internal-error"),
    JWT_KEY_NOT_CONFIGURED("jwt-key-not-configured");

    private final String value;

    DocLogErrorType(String value) {
        this.value = value;
    }

    /// kebab-case 문자열 — `error.type` 속성 값으로 직접 사용된다.
    public String value() {
        return value;
    }
}

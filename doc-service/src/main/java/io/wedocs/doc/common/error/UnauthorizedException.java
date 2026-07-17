package io.wedocs.doc.common.error;

/// 로그인 실패 → 401. 미존재 계정과 비밀번호 불일치를 하나의 코드·문구(INVALID_CREDENTIALS)로 붕괴시킨다 —
/// 계정 존재를 응답·로그 어디에도 열거하지 않는다(secure-coding P4).
public final class UnauthorizedException extends DomainException {

    public UnauthorizedException() {
        super(DocErrorCode.INVALID_CREDENTIALS);
    }
}

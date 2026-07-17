package io.wedocs.doc.common.error;

/// 현재 상태와 충돌하는 요청(중복 생성·불변식 위반) → 409.
public final class ConflictException extends DomainException {

    public ConflictException(DocErrorCode code) {
        super(require(code, DocErrorCode.Category.CONFLICT));
    }

    /// 제약 위반(동시 삽입 레이스)의 원인 체인 보존용(error-handling P4).
    public ConflictException(DocErrorCode code, Throwable cause) {
        super(require(code, DocErrorCode.Category.CONFLICT), cause);
    }
}

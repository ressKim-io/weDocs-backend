package io.wedocs.doc.common.error;

/// 클라이언트가 제공한 값이 도메인 제약을 위반(크기 초과·형식 오류 등) → 400.
public final class BadRequestException extends DomainException {

    public BadRequestException(DocErrorCode code) {
        super(require(code, DocErrorCode.Category.BAD_REQUEST));
    }
}

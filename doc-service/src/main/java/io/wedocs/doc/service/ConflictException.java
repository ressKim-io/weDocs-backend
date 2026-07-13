package io.wedocs.doc.service;

/// 현재 상태와 충돌하는 요청(중복 생성·불변식 위반) → HTTP 409.
public abstract class ConflictException extends DomainException {

    protected ConflictException(String message) {
        super(message);
    }

    protected ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

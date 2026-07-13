package io.wedocs.doc.service;

/// 이미 가입된 이메일로 재가입 시도. 메시지에 이메일을 싣지 않는다 — 응답·로그 어디에도 PII 최소화.
public class EmailAlreadyUsedException extends ConflictException {

    private static final String MESSAGE = "email already in use";

    public EmailAlreadyUsedException() {
        super(MESSAGE);
    }

    /// unique 제약 위반(동시 가입 레이스)에서 원인 체인 보존용(error-handling P4).
    public EmailAlreadyUsedException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

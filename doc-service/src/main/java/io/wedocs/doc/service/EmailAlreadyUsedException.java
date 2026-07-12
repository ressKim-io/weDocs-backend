package io.wedocs.doc.service;

/// 이미 가입된 이메일로 재가입 시도. 메시지에 이메일을 싣지 않는다 — 응답·로그 어디에도 PII 최소화.
public class EmailAlreadyUsedException extends ConflictException {

    public EmailAlreadyUsedException() {
        super("email already in use");
    }
}

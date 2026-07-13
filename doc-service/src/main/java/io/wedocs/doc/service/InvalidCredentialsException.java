package io.wedocs.doc.service;

/// 로그인 실패 — 미존재 계정과 비밀번호 불일치를 하나의 타입·하나의 메시지로 붕괴시킨다
/// (계정 존재 비노출, secure-coding P4). 원인 구분은 응답이 아니라 내부 로그로도 남기지 않는다(열거 방지).
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}

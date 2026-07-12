package io.wedocs.doc.service;

/// 도메인 실패의 공통 상위 타입 (error-handling P2 — 실패는 타입이다).
/// HTTP 매핑은 GlobalExceptionHandler 한 곳(P1), gRPC 매핑은 DocServiceImpl 경계.
/// 메시지는 클라이언트에 그대로 나간다 — 서버 내부 상태(설정·기대값·스택)를 싣지 않는다(secure-coding P4).
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.wedocs.doc.api;

import io.wedocs.doc.service.ConflictException;
import io.wedocs.doc.service.DomainException;
import io.wedocs.doc.service.ForbiddenException;
import io.wedocs.doc.service.InvalidCredentialsException;
import io.wedocs.doc.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/// 도메인 예외 → RFC 9457 ProblemDetail 중앙 매핑 (error-handling P1/P3).
/// 프레임워크 예외(검증 400 등)는 spring.mvc.problemdetails.enabled=true가 동일 포맷으로 처리.
/// detail은 DomainException 메시지 그대로 — 내부 상태를 싣지 않는 규약은 예외 타입이 소유(P4).
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final String ERROR_TYPE_BASE = "https://wedocs.io/errors/";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "conflict", e);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-credentials", e);
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail handleForbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", e);
    }

    /// 매핑 없이 추가된 신규 DomainException 서브타입의 안전망 — 500이되 에러 스키마는 유지.
    /// 여기 도달 = 이 핸들러 갱신 누락(프로그래밍 오류) → detail은 고정 문구, 상세는 로그로만(P4/P6).
    @ExceptionHandler(DomainException.class)
    ProblemDetail handleUnmappedDomain(DomainException e) {
        log.error("unmapped domain exception: type={}", e.getClass().getSimpleName(), e);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error");
        detail.setType(URI.create(ERROR_TYPE_BASE + "internal"));
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String typeSlug, DomainException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setType(URI.create(ERROR_TYPE_BASE + typeSlug));
        return detail;
    }
}

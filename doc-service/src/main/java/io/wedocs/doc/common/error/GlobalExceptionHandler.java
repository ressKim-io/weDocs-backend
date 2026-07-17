package io.wedocs.doc.common.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/// 도메인 예외 → RFC 9457 ProblemDetail 중앙 매핑 (error-handling P1/P3/P7). 상태·문구·타입은
/// ErrorCode 카탈로그에서만 읽는다 — 핸들러에 상태코드/문구 하드코딩 없음.
/// 5xx(불변식)는 detail을 고정 "unexpected error"로 — 내부 상세는 로그로만(P4/P6).
/// 프레임워크 예외(검증 400 등)는 spring.mvc.problemdetails.enabled=true가 동일 포맷으로 처리.
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://wedocs.io/errors/";

    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException e) {
        DocErrorCode code = e.code();
        if (code.http().is5xxServerError()) {
            log.error("domain invariant broken: code={}", code.slug(), e);
            return problem(code, "unexpected error");
        }
        return problem(code, code.message());
    }

    private static ProblemDetail problem(DocErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.http(), detail);
        pd.setType(URI.create(TYPE_BASE + code.slug()));
        pd.setProperty("code", code.slug()); // detail 파싱 금지 → 기계 정보는 확장 멤버(RFC 9457)
        return pd;
    }
}

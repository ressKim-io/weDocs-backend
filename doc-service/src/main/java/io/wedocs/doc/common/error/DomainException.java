package io.wedocs.doc.common.error;

/// 도메인 실패의 공통 상위 타입 (error-handling P2·P7). 실패 종류는 카테고리 서브타입 + ErrorCode로 표현한다 —
/// 호출자·핸들러는 카테고리 타입으로 매칭하고, 상태·문구는 code()의 필드에서 읽는다.
/// HTTP 매핑은 GlobalExceptionHandler 한 곳(P1), gRPC 매핑은 DocServiceImpl 경계 한 곳.
/// sealed로 카테고리 5종만 허용 — require() 검증을 우회하는 6번째 서브타입 신설을 컴파일러가 막는다(design-patterns P4).
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, ConflictException, ForbiddenException, UnauthorizedException, InvariantViolationException {

    private final DocErrorCode code;

    protected DomainException(DocErrorCode code) {
        super(code.message());
        this.code = code;
    }

    protected DomainException(DocErrorCode code, Throwable cause) {
        super(code.message(), cause);
        this.code = code;
    }

    /// 로그 전용 상세를 예외 메시지로 싣는 경로 — 클라이언트 노출 문구는 여전히 code.message()(핸들러가 사용).
    /// 불변식 위반처럼 내부 상태를 로그에만 남겨야 할 때 쓴다(secure-coding P4).
    protected DomainException(DocErrorCode code, String logDetail) {
        super(logDetail);
        this.code = code;
    }

    public DocErrorCode code() {
        return code;
    }

    /// 카테고리 예외 생성자의 코드-타입 정합 관문 — 잘못된 카테고리의 코드를 실으면 생성 시점에 거부.
    protected static DocErrorCode require(DocErrorCode code, DocErrorCode.Category expected) {
        if (code.category() != expected) {
            throw new IllegalArgumentException(code + " is not a " + expected + " error");
        }
        return code;
    }
}

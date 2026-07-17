package io.wedocs.doc.common.error;

/// 정상 경로에서 도달 불가한 서버 불변식 위반(예: FK 결손) → 500. 내부 상세는 로그로만 남기고,
/// 클라이언트에는 고정 문구("unexpected error")만 나간다(secure-coding P4). raw IllegalState로
/// 도메인 밖 오류를 표현하지 않는다(error-handling P7) — 카탈로그 안에서 표현한다.
public final class InvariantViolationException extends DomainException {

    public InvariantViolationException(String logDetail) {
        super(DocErrorCode.INVARIANT_BROKEN, logDetail);
    }
}

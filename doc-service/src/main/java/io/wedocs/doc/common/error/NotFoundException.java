package io.wedocs.doc.common.error;

/// "없음"이 진짜 에러인 조회 실패 → 404. 권한 없는 접근도 존재 비노출을 위해 이 계열로 응답한다
/// (secure-coding P3 IDOR — 1b CheckPermission과 동일 원칙).
public final class NotFoundException extends DomainException {

    public NotFoundException(DocErrorCode code) {
        super(require(code, DocErrorCode.Category.NOT_FOUND));
    }
}

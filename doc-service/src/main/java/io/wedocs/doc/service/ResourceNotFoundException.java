package io.wedocs.doc.service;

/// "없음"이 진짜 에러인 조회 실패 → HTTP 404. 권한 없는 접근도 존재 비노출을 위해
/// 이 계열로 응답한다(secure-coding P3 IDOR — 1b CheckPermission과 동일 원칙).
public abstract class ResourceNotFoundException extends DomainException {

    protected ResourceNotFoundException(String message) {
        super(message);
    }
}

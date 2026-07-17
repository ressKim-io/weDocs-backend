package io.wedocs.doc.service;

/// 인증은 됐으나 권한이 부족한 요청 → HTTP 403. 존재를 이미 아는 요청자(읽기 통과·멤버)에게만
/// 도달해야 한다 — 존재 자체를 숨겨야 하면 403이 아니라 404(ResourceNotFoundException) 계열을 쓴다(P3).
/// 메시지는 고정 문구 — 어떤 권한이 왜 부족한지 서버 내부 판단을 싣지 않는다(P4).
public class ForbiddenException extends DomainException {

    public ForbiddenException() {
        super("insufficient permission");
    }
}

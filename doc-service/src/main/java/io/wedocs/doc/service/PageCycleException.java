package io.wedocs.doc.service;

/// 트리 불변식을 깨는 이동 요청 → HTTP 409 (ADR-0012: 트리 동시성=관계형 직렬화 + 사이클 검사).
public class PageCycleException extends ConflictException {

    private PageCycleException(String message) {
        super(message);
    }

    public static PageCycleException cycle() {
        return new PageCycleException("page move would create a cycle");
    }

    /// 조상 탐색 상한 도달 = 사이클 여부를 확인할 수 없는 상태 — fail-closed로 거부
    /// (PermissionService MAX_ANCESTOR_DEPTH와 동일 원칙, secure-coding P2).
    public static PageCycleException depthCapExceeded() {
        return new PageCycleException("page tree depth cap exceeded");
    }
}

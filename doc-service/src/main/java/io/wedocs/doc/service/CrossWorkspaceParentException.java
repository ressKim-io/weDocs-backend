package io.wedocs.doc.service;

/// 다른 워크스페이스의 페이지를 부모로 지정(생성·이동) — 트리는 워크스페이스에 닫혀 있다(ADR-0012)
/// → HTTP 409. 이 예외에 도달한 요청자는 부모 읽기를 이미 통과했으므로 404 비노출이 불필요하다.
public class CrossWorkspaceParentException extends ConflictException {

    public CrossWorkspaceParentException() {
        super("parent page belongs to a different workspace");
    }
}

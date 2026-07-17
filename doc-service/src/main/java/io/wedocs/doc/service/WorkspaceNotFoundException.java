package io.wedocs.doc.service;

import java.util.UUID;

/// 미존재 워크스페이스 참조와 비멤버 접근을 하나의 404로 붕괴 — 워크스페이스 존재 비노출
/// (PageNotFoundException의 read 권한 없는 접근과 동일 원칙, secure-coding P3 IDOR).
public class WorkspaceNotFoundException extends ResourceNotFoundException {

    public WorkspaceNotFoundException(UUID workspaceId) {
        super("workspace not found: " + workspaceId);
    }
}

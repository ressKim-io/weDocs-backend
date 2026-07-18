package io.wedocs.doc.page;


import java.util.UUID;

/// 트리 조립에 필요한 구조 필드까지만 — 내용(CRDT)은 엔진 경로(ADR-0012), 감사 타임스탬프 비노출.
public record PageResponse(UUID id, UUID workspaceId, UUID parentId, String title, int position, boolean archived) {

    public static PageResponse from(Page page) {
        return new PageResponse(page.getId(), page.getWorkspaceId(), page.getParentId(),
                page.getTitle(), page.getPosition(), page.isArchived());
    }
}

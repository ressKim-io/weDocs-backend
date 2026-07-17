package io.wedocs.doc.api.dto;

import io.wedocs.doc.domain.Workspace;

import java.util.UUID;

/// 엔티티 비노출(layering P5) — 감사 타임스탬프는 API 표면에 싣지 않는다.
public record WorkspaceResponse(UUID id, String name, UUID ownerId) {

    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getOwnerId());
    }
}

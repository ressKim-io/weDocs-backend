package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/// 워크스페이스 멤버십(baseline 권한). role: owner|member (PRD §4.1).
/// V1__init_page_tree.sql에 created_at/updated_at 컬럼이 없다 — Base*Entity 상속 금지
/// (상속 시 ddl-auto=validate가 즉시 스키마 불일치로 실패).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {

    @EmbeddedId
    private WorkspaceMemberId id;

    @Convert(converter = WorkspaceRoleConverter.class)
    @Column(nullable = false, length = 16)
    private WorkspaceRole role;

    public WorkspaceMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
        this.id = new WorkspaceMemberId(workspaceId, userId);
        this.role = role;
    }

    public UUID getWorkspaceId() {
        return id.getWorkspaceId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }
}

package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/// 위키의 한 페이지 = 한 CRDT 문서. 자기참조 트리(parent_id NULL=루트).
/// 트리 동시성은 관계형(doc-service 트랜잭션), 내용 동시성은 CRDT 엔진 (ADR-0012).
@Entity
@Table(name = "pages")
public class Page {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /// NULL = 루트 페이지 (proto DocMeta는 ""로 매핑).
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String title;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean archived;

    protected Page() { }

    public Page(UUID id, UUID workspaceId, UUID parentId, String title, int position, boolean archived) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.parentId = parentId;
        this.title = title;
        this.position = position;
        this.archived = archived;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getParentId() { return parentId; }
    public String getTitle() { return title; }
    public int getPosition() { return position; }
    public boolean isArchived() { return archived; }
}

package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/// 영속화된 CRDT 상태 (복원용). 페이지당 최신 1행 UPSERT (ADR-0013).
/// version = 엔진 권위 단조 카운터. snapshot = lib0 v1 encode_state_as_update.
/// created_at = 행 최초 기록 시각(BaseCreatedEntity). 실 UPSERT(INSERT ON CONFLICT) 쓰기 시각 의미는 SaveSnapshot RPC(1b/Phase3)에서 확정.
@Entity
@Table(name = "page_snapshots")
public class PageSnapshot extends BaseCreatedEntity {

    @Id
    @Column(name = "page_id")
    private UUID pageId;

    @Column(nullable = false)
    private byte[] snapshot;

    @Column(nullable = false)
    private long version;

    protected PageSnapshot() { }

    public PageSnapshot(UUID pageId, byte[] snapshot, long version) {
        this.pageId = pageId;
        this.snapshot = snapshot;
        this.version = version;
    }

    public UUID getPageId() { return pageId; }
    public byte[] getSnapshot() { return snapshot; }
    public long getVersion() { return version; }
}

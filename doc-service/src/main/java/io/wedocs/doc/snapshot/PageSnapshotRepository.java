package io.wedocs.doc.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/// 페이지 스냅샷(CRDT 직렬 상태) 영속화 — doc-service ↔ 엔진 간 스냅샷 저장·로드 경로.
public interface PageSnapshotRepository extends JpaRepository<PageSnapshot, UUID> {
}

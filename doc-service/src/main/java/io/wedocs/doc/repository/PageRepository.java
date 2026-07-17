package io.wedocs.doc.repository;

import io.wedocs.doc.domain.Page;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByParentId(UUID parentId);
    List<Page> findByWorkspaceId(UUID workspaceId);

    /// 트리 표시용 평면 목록 — 아카이브 제외, position→생성순 정렬, 상한은 호출자(P2) 명시.
    List<Page> findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(UUID workspaceId, Limit limit);
}

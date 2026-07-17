package io.wedocs.doc.repository;

import io.wedocs.doc.domain.Page;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByParentId(UUID parentId);
    List<Page> findByWorkspaceId(UUID workspaceId);

    /// 트리 표시용 평면 목록 — 아카이브 "포함" 전체 로드(도달성 판정은 서비스가 수행),
    /// position→생성순 정렬, 상한은 호출자(P2) 명시. 아카이브 행을 빼고 로드하면 아카이브
    /// 부모의 비아카이브 자손이 고아로 노출된다(1c 게이트 HIGH-1).
    List<Page> findByWorkspaceIdOrderByPositionAscCreatedAtAsc(UUID workspaceId, Limit limit);
}

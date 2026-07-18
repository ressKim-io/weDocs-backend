package io.wedocs.doc.page;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    /// 부모의 직속 자식 — 테스트 전용(트리 관계 검증). 프로덕션 트리 로드는 아래 상한 쿼리를 쓴다.
    List<Page> findByParentId(UUID parentId);

    /// 트리 표시용 평면 목록 — 아카이브 제외, position→생성순 정렬, 상한은 호출자(P2) 명시.
    /// 상한 예산은 "활성 페이지" 기준(아카이브 행이 슬롯을 잠식하지 않도록 WHERE에서 선필터).
    /// 아카이브 부모의 자손 숨김은 서비스의 도달성 판정이 수행 — 아카이브 부모는 이 로드셋에
    /// 없으므로 그 자손은 "부모 미로드"로 배제된다(1c 게이트 HIGH-1).
    List<Page> findByWorkspaceIdAndArchivedFalseOrderByPositionAscCreatedAtAsc(UUID workspaceId, Limit limit);
}

package io.wedocs.doc.workspace;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// 워크스페이스 멤버 복합키 저장소 — 멤버십 확인과 사용자별 워크스페이스 조회를 지원한다.
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    /// 언더스코어(Id_WorkspaceId)로 @EmbeddedId 중첩 프로퍼티 경로를 명시 — 이 코드베이스 최초의
    /// 복합키 derived query라 파싱 모호성을 없애는 쪽을 택함.
    Optional<WorkspaceMember> findById_WorkspaceIdAndId_UserId(UUID workspaceId, UUID userId);

    List<WorkspaceMember> findById_UserId(UUID userId, Limit limit);
}

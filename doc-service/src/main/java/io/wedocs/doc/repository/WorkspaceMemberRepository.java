package io.wedocs.doc.repository;

import io.wedocs.doc.domain.WorkspaceMember;
import io.wedocs.doc.domain.WorkspaceMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    /// 언더스코어(Id_WorkspaceId)로 @EmbeddedId 중첩 프로퍼티 경로를 명시 — 이 코드베이스 최초의
    /// 복합키 derived query라 파싱 모호성을 없애는 쪽을 택함.
    Optional<WorkspaceMember> findById_WorkspaceIdAndId_UserId(UUID workspaceId, UUID userId);

    List<WorkspaceMember> findById_UserId(UUID userId);
}

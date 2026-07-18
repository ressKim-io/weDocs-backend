package io.wedocs.doc.workspace;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /// 워크스페이스 행 SELECT ... FOR UPDATE — 페이지 트리 이동의 직렬화 락(ADR-0012).
    /// 파생 쿼리의 WithLock은 서술용 무시 토큰 — 실제 잠금은 @Lock이 부여한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workspace> findWithLockById(UUID id);
}

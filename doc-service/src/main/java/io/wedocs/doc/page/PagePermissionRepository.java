package io.wedocs.doc.page;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/// 페이지별 사용자 권한(명시적 공유) 저장소.
public interface PagePermissionRepository extends JpaRepository<PagePermission, PagePermissionId> {

    Optional<PagePermission> findById_PageIdAndId_UserId(UUID pageId, UUID userId);
}

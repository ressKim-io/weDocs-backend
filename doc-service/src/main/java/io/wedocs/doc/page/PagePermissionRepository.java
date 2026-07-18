package io.wedocs.doc.page;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PagePermissionRepository extends JpaRepository<PagePermission, PagePermissionId> {

    Optional<PagePermission> findById_PageIdAndId_UserId(UUID pageId, UUID userId);
}

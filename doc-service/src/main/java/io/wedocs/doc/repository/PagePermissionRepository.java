package io.wedocs.doc.repository;

import io.wedocs.doc.domain.PagePermission;
import io.wedocs.doc.domain.PagePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PagePermissionRepository extends JpaRepository<PagePermission, PagePermissionId> {

    Optional<PagePermission> findById_PageIdAndId_UserId(UUID pageId, UUID userId);
}

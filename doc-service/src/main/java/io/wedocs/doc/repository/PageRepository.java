package io.wedocs.doc.repository;

import io.wedocs.doc.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByParentId(UUID parentId);
    List<Page> findByWorkspaceId(UUID workspaceId);
}

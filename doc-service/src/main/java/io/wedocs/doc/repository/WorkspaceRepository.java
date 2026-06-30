package io.wedocs.doc.repository;

import io.wedocs.doc.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
}

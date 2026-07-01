package io.wedocs.doc.repository;

import io.wedocs.doc.domain.PageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PageSnapshotRepository extends JpaRepository<PageSnapshot, UUID> {
}

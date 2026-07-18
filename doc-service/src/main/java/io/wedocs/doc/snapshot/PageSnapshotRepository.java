package io.wedocs.doc.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PageSnapshotRepository extends JpaRepository<PageSnapshot, UUID> {
}

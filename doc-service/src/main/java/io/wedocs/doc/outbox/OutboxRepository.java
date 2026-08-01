package io.wedocs.doc.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/// outbox 행 영속화. M2는 insert만, relay 조회(published_at IS NULL ORDER BY id)는 M4.
interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}

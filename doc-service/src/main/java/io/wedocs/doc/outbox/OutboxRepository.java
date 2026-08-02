package io.wedocs.doc.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/// outbox 행 영속화 + cleanup.
/// M2는 insert + cleanup만, relay 조회(published_at IS NULL ORDER BY id)는 M4.
interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /// 발행 완료 후 보존 기간이 지난 행을 삭제한다.
    int deleteByPublishedAtIsNotNullAndCreatedAtBefore(Instant cutoff);

    /// M2 안전장치: relay 없는 동안 오래된 미발행 행을 정리한다.
    int deleteByPublishedAtIsNullAndCreatedAtBefore(Instant cutoff);
}

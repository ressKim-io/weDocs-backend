package io.wedocs.doc.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/// Outbox 행 주기 정리. 무한 증가를 방지한다.
///
/// - 발행 완료(published_at != null) 행: 7일 보존 후 삭제 (M4 relay 동작 시)
/// - 미발행(published_at IS NULL) 행: 30일 보존 후 삭제 (M2 안전장치 — relay 부재 기간)
///
/// M4에서 relay가 마킹을 시작하면 발행 완료 행 정리가 실질적으로 동작한다.
/// 미발행 30일 삭제는 relay 도입 후 제거하거나, 장기 미발행 감지 알림으로 전환한다.
@Slf4j
@RequiredArgsConstructor
@Component
class OutboxCleanupJob {

    static final Duration PUBLISHED_RETENTION = Duration.ofDays(7);
    static final Duration UNPUBLISHED_RETENTION = Duration.ofDays(30);

    private final OutboxRepository repository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanup() {
        Instant publishedCutoff = Instant.now().minus(PUBLISHED_RETENTION);
        int publishedDeleted = repository.deleteByPublishedAtIsNotNullAndCreatedAtBefore(publishedCutoff);

        Instant unpublishedCutoff = Instant.now().minus(UNPUBLISHED_RETENTION);
        int unpublishedDeleted = repository.deleteByPublishedAtIsNullAndCreatedAtBefore(unpublishedCutoff);

        if (publishedDeleted + unpublishedDeleted > 0) {
            log.info("outbox cleanup: published={} unpublished={}", publishedDeleted, unpublishedDeleted);
        }
    }
}

package io.wedocs.doc.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/// 트랜잭셔널 outbox 행 (ADR-0015). 비즈니스 변경과 같은 트랜잭션에서 insert되어 원자성을 보장한다.
/// relay(M4)가 `published_at IS NULL`을 순서대로 발행한 뒤 마킹한다.
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /// 변경 주체 (= user_id). 감사·인덱서에서 "누가" 필수. 앱 레벨에서 NOT NULL 강제.
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    /// 이벤트 대상 식별자 — page 변경이면 page_id.
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /// aggregate 종류 (e.g. "page"). relay가 토픽 라우팅에 사용.
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 32)
    private String aggregateType;

    /// 이벤트 유형(점 구분): `page.created`, `page.renamed`, `page.moved`, `page.archived`.
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    /// 이벤트 본문(JSON). M4 소비자(인덱서)가 멱등 처리에 필요한 최소 정보를 담는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    /// W3C traceparent — OTel context를 Kafka 경유 전파(가드레일 4). 현 단계(M2)는 null.
    @Column(name = "traceparent", updatable = false, length = 64)
    private String traceparent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /// relay(M4)가 발행 후 마킹. NULL = 미발행.
    @Column(name = "published_at")
    private Instant publishedAt;

    OutboxEvent(UUID actorId, UUID aggregateId, String aggregateType,
                String eventType, String payload, String traceparent) {
        this.actorId = actorId;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.traceparent = traceparent;
        this.createdAt = Instant.now();
    }
}

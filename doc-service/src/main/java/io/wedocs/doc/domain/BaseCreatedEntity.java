package io.wedocs.doc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/// 생성 시각만 갖는 엔티티 공통 베이스(통째 교체되는 스냅샷 등).
/// 행위자(created_by)는 1c 인증 후 — AuditorAware 소스가 생기면 추가(ADR-0014).
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseCreatedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }
}

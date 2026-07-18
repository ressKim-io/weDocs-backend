package io.wedocs.doc.common.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/// 생성 + 수정 시각을 갖는 가변 엔티티 공통 베이스.
/// updated_at은 JPA Auditing이 매 저장마다 갱신(DB default now()는 INSERT 1회뿐 → 수정 시각 박제 방지).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreatedEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

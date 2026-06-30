package io.wedocs.doc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/// JPA Auditing 활성화 — @CreatedDate/@LastModifiedDate 콜백 등록.
/// 행위자 감사(@CreatedBy/@LastModifiedBy + AuditorAware)는 1c 인증 후.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}

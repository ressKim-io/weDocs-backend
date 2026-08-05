package io.wedocs.gateway.architecture;

/// ws-gateway ArchUnit 규칙에서 사용하는 위반 메시지 템플릿과 어노테이션 FQCN 상수.
///
/// enum이 아닌 final class인 이유: String.format 템플릿 + 테스트 전용 유틸리티 + .class 리터럴 혼용.
final class ArchRuleConstants {

    private ArchRuleConstants() {}

    // ── 위반 메시지 템플릿 ──

    /// @Value 어노테이션 사용 위반. %s=필드명, %s=클래스명.
    static final String VALUE_ANNOTATION_VIOLATION =
            "Field '%s' in %s uses @Value annotation. "
                    + "Use @ConfigurationProperties record instead for type-safe configuration binding.";

    /// Spring 빈 필드 final 위반. %s=필드명, %s=클래스명.
    static final String FIELD_NOT_FINAL_VIOLATION =
            "Field '%s' in %s is not final. "
                    + "Spring bean fields should be final for immutability "
                    + "(use @RequiredArgsConstructor + private final).";

    // ── 로깅 아키텍처 규칙 위반 메시지 ──

    /// OpenTelemetry 컴파일 타임 참조 위반. %s=소스 클래스, %s=타겟 클래스.
    static final String OTEL_COMPILE_TIME_VIOLATION =
            "Class %s has a compile-time dependency on %s. "
                    + "OpenTelemetry must be used via runtime javaagent only — no compile-time references allowed.";

    /// 로깅 규약 클래스 패키지 위치 위반. %s=클래스명, %s=실제 패키지.
    static final String LOGGING_CONVENTION_PACKAGE_VIOLATION =
            "Class %s resides in %s but logging convention implementations "
                    + "must reside in the 'io.wedocs.gateway.common.logging' package.";

    /// 크로스 모듈 패키지 참조 위반. %s=소스 클래스, %s=타겟 클래스.
    static final String CROSS_MODULE_DEPENDENCY_VIOLATION =
            "Class %s depends on %s. "
                    + "ws-gateway must not reference doc-service packages (io.wedocs.doc..).";

    // ── 어노테이션 FQCN (classpath에 해당 클래스 미존재) ──

    /// spring-tx가 ws-gateway classpath에 미존재하므로 .class 리터럴 대신 문자열 상수로 선언.
    /// ws-gateway는 JPA/spring-tx를 사용하지 않는 WebSocket 전용 모듈이다.
    /// @see <a href="https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Transactional.html">Transactional</a>
    static final String TRANSACTIONAL_FQCN =
            "org.springframework.transaction.annotation.Transactional";
}

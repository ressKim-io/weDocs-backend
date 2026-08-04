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

    // ── 어노테이션 FQCN (classpath에 해당 클래스 미존재) ──

    /// spring-tx가 ws-gateway classpath에 미존재하므로 .class 리터럴 대신 문자열 상수로 선언.
    /// ws-gateway는 JPA/spring-tx를 사용하지 않는 WebSocket 전용 모듈이다.
    /// @see <a href="https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Transactional.html">Transactional</a>
    static final String TRANSACTIONAL_FQCN =
            "org.springframework.transaction.annotation.Transactional";
}

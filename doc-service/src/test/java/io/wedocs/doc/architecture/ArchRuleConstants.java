package io.wedocs.doc.architecture;

/// doc-service ArchUnit 규칙에서 사용하는 위반 메시지 템플릿 상수.
///
/// enum이 아닌 final class인 이유: String.format 템플릿 + 테스트 전용 유틸리티 혼용.
final class ArchRuleConstants {

    private ArchRuleConstants() {}

    // ── 위반 메시지 템플릿 ──

    /// @Value 어노테이션 사용 위반. %s=필드명, %s=클래스명.
    static final String VALUE_ANNOTATION_VIOLATION =
            "Field '%s' in %s uses @Value annotation. "
                    + "Use @ConfigurationProperties record instead for type-safe configuration binding.";

    /// Controller try-catch 위반. %s=메서드명, %s=클래스명, %s=잡힌 예외 목록.
    static final String CONTROLLER_TRY_CATCH_VIOLATION =
            "Method %s in %s contains a try-catch block catching %s. "
                    + "Controllers should delegate exception handling to GlobalExceptionHandler.";

    /// Spring 빈 필드 final 위반. %s=필드명, %s=클래스명.
    static final String FIELD_NOT_FINAL_VIOLATION =
            "Field '%s' in %s is not final. "
                    + "Spring bean fields should be final for immutability "
                    + "(use @RequiredArgsConstructor + private final).";

    /// checked exception catch 시 cause 체이닝 누락. %s=메서드명, %s=클래스명, %s=잡힌 예외 목록.
    static final String CAUSE_CHAINING_VIOLATION =
            "Method %s in %s catches checked exception(s) %s but creates "
                    + "DomainException without cause chaining. "
                    + "Use the 2-arg constructor (code, cause) to preserve the original exception.";
}

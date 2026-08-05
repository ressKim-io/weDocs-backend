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

    // ── 로깅 아키텍처 규칙 위반 메시지 ──

    /// OpenTelemetry 컴파일 타임 참조 위반. %s=소스 클래스, %s=타겟 클래스.
    static final String OTEL_COMPILE_TIME_VIOLATION =
            "Class %s has a compile-time dependency on %s. "
                    + "OpenTelemetry must be used via runtime javaagent only — no compile-time references allowed.";

    /// 로깅 규약 클래스 패키지 위치 위반. %s=클래스명, %s=실제 패키지.
    static final String LOGGING_CONVENTION_PACKAGE_VIOLATION =
            "Class %s resides in %s but logging convention implementations "
                    + "must reside in the 'io.wedocs.doc.common.logging' package.";

    /// 크로스 모듈 패키지 참조 위반. %s=소스 클래스, %s=타겟 클래스.
    static final String CROSS_MODULE_DEPENDENCY_VIOLATION =
            "Class %s depends on %s. "
                    + "doc-service must not reference ws-gateway packages (io.wedocs.gateway..).";

    /// checked exception catch 시 cause 체이닝 누락. %s=메서드명, %s=클래스명, %s=잡힌 예외 목록.
    static final String CAUSE_CHAINING_VIOLATION =
            "Method %s in %s catches checked exception(s) %s but creates "
                    + "DomainException without cause chaining. "
                    + "Use the 2-arg constructor (code, cause) to preserve the original exception.";
}

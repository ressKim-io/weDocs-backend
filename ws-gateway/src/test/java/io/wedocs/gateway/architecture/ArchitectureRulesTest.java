package io.wedocs.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ws-gateway 아키텍처 규칙 자동 검증 — CI에서 매 빌드마다 실행.
 *
 * <p>gRPC 클라이언트 계층 분리, 패키지 간 순환 참조 금지, 불변성을 강제한다.
 * ws-gateway는 REST Controller 없이 WebSocket 핸들러 기반으로 동작하므로,
 * doc-service와 다른 규칙 집합을 적용한다.
 *
 * <p>기존 auth↔ws 순환 참조는 handshake 공유 패키지 도입 + WebSocketConfig의 config 패키지 이동으로 해소 완료.
 */
@AnalyzeClasses(packages = "io.wedocs.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // ──────────────────────────────────────────────────────────────────
    // 계층 분리 규칙
    // ──────────────────────────────────────────────────────────────────

    /**
     * gRPC 클라이언트 패키지는 auth 패키지에 의존할 수 없다.
     * gRPC 클라이언트는 순수 통신 계층이며 인증 로직과 무관해야 한다.
     */
    @ArchTest
    static final ArchRule grpc_should_not_depend_on_auth =
            noClasses().that().resideInAPackage("..grpc..")
                    .should().dependOnClassesThat().resideInAPackage("..auth..");

    /**
     * gRPC 클라이언트 패키지는 WebSocket 핸들러 패키지에 의존할 수 없다.
     * gRPC 클라이언트는 프레젠테이션 계층(ws)에 독립적이어야 한다.
     */
    @ArchTest
    static final ArchRule grpc_should_not_depend_on_ws =
            noClasses().that().resideInAPackage("..grpc..")
                    .should().dependOnClassesThat().resideInAPackage("..ws..");

    /**
     * feature 패키지(auth, grpc, ws, config, handshake) 간 순환 참조를 금지한다.
     *
     * <p>기존 auth↔ws 순환은 handshake 공유 패키지 + config 패키지 도입으로 해소 완료.
     * 새로운 순환 도입을 방지한다.
     */
    @ArchTest
    static final ArchRule no_cyclic_dependencies_between_packages =
            slices().matching("io.wedocs.gateway.(*)..")
                    .should().beFreeOfCycles();

    // ──────────────────────────────────────────────────────────────────
    // 불변성 규칙 (Req 4.1): Spring 빈 필드 final 강제
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@code @Component} 빈의 인스턴스 필드는 모두 final이어야 한다.
     * 제외: {@code @Configuration} 클래스, static 필드, {@code @Autowired} 어노테이션 필드.
     */
    @ArchTest
    static final ArchRule spring_bean_fields_should_be_final =
            classes().that().areAnnotatedWith(Component.class)
                    .should(haveAllInstanceFieldsFinal());

    // ──────────────────────────────────────────────────────────────────
    // 설정 외부화 규칙 (Req 12.1): @Value 사용 금지
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@code @Value} 어노테이션 사용을 금지한다.
     * 타입 안전한 {@code @ConfigurationProperties} record 패턴을 대신 사용해야 한다.
     */
    @ArchTest
    static final ArchRule value_annotation_should_not_be_used =
            noClasses().should(useValueAnnotationOnFields());

    // ──────────────────────────────────────────────────────────────────
    // @Transactional 유입 차단: spring-tx 미보유 모듈
    // ──────────────────────────────────────────────────────────────────

    /**
     * ws-gateway는 JPA/spring-tx를 사용하지 않으므로 {@code @Transactional} 유입을 차단한다.
     * spring-tx가 classpath에 없으므로 FQCN 문자열 상수 + {@code beAnnotatedWith(String)} 오버로드 사용.
     */
    @ArchTest
    static final ArchRule transactional_should_not_be_used =
            noClasses().should().beAnnotatedWith(ArchRuleConstants.TRANSACTIONAL_FQCN);

    // ──────────────────────────────────────────────────────────────────
    // Custom Conditions
    // ──────────────────────────────────────────────────────────────────

    private static ArchCondition<JavaClass> useValueAnnotationOnFields() {
        return new ArchCondition<>("use @Value annotation on fields") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (field.isAnnotatedWith(Value.class)) {
                        String message = String.format(
                                ArchRuleConstants.VALUE_ANNOTATION_VIOLATION,
                                field.getName(), javaClass.getName());
                        events.add(SimpleConditionEvent.violated(field, message));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAllInstanceFieldsFinal() {
        return new ArchCondition<>("have all instance fields declared as final") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                // @Configuration 클래스는 제외
                if (javaClass.isAnnotatedWith(Configuration.class)) {
                    return;
                }
                for (JavaField field : javaClass.getFields()) {
                    // static 필드 제외
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    // synthetic 필드 제외 (컴파일러 생성)
                    if (field.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                        continue;
                    }
                    // @Autowired 어노테이션 필드 제외
                    if (field.isAnnotatedWith(Autowired.class)) {
                        continue;
                    }
                    if (!field.getModifiers().contains(JavaModifier.FINAL)) {
                        String message = String.format(
                                ArchRuleConstants.FIELD_NOT_FINAL_VIOLATION,
                                field.getName(), javaClass.getName());
                        events.add(SimpleConditionEvent.violated(field, message));
                    }
                }
            }
        };
    }
}

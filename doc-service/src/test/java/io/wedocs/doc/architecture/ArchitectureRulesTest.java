package io.wedocs.doc.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.wedocs.doc.common.error.DomainException;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.Lifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 아키텍처 규칙 자동 검증 — CI에서 매 빌드마다 실행.
 *
 * <p>계층 분리, HTTP 객체 참조 금지, feature 패키지 간 순환 참조 금지,
 * 불변성 강제, 예외 처리 일관성을 강제한다.
 */
@AnalyzeClasses(packages = "io.wedocs.doc", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // ──────────────────────────────────────────────────────────────────
    // 계층 분리 규칙
    // ──────────────────────────────────────────────────────────────────

    /**
     * Controller는 Repository에 직접 의존할 수 없다.
     * Controller → Service → Repository 계층 흐름을 강제.
     */
    @ArchTest
    static final ArchRule controllers_should_not_depend_on_repositories =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    /**
     * Service 계층은 HTTP 객체(jakarta.servlet..)를 참조할 수 없다.
     * Service는 프레젠테이션 계층에 독립적이어야 한다.
     */
    @ArchTest
    static final ArchRule services_should_not_use_http_objects =
            noClasses().that().haveSimpleNameEndingWith("Service")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..");

    /**
     * feature 패키지 간 순환 참조를 금지한다.
     * 각 feature(auth, page, workspace 등)는 독립적으로 유지해야 한다.
     */
    @ArchTest
    static final ArchRule no_cyclic_dependencies_between_features =
            slices().matching("io.wedocs.doc.(*)..")
                    .should().beFreeOfCycles();

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
    // 예외 처리 규칙 (Req 5.1): Controller 내부 try-catch 금지
    // ──────────────────────────────────────────────────────────────────

    /**
     * Controller 내부에서 try-catch를 사용할 수 없다.
     * 예외 처리는 GlobalExceptionHandler로 위임해야 한다.
     */
    @ArchTest
    static final ArchRule controllers_should_not_use_try_catch =
            classes().that().haveSimpleNameEndingWith("Controller")
                    .should(notContainTryCatchBlocks());

    // ──────────────────────────────────────────────────────────────────
    // 불변성 규칙 (Req 4.1): Spring 빈 필드 final 강제
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@code @Service}, {@code @Component}, {@code @RestController} 빈의 인스턴스 필드는 모두 final이어야 한다.
     * 제외: {@code @Entity}, {@code @Configuration}, {@code @MappedSuperclass} 클래스, static 필드,
     * {@code @Autowired} 어노테이션 필드.
     */
    @ArchTest
    static final ArchRule spring_bean_fields_should_be_final =
            classes().that().areAnnotatedWith(Service.class)
                    .or().areAnnotatedWith(Component.class)
                    .or().areAnnotatedWith(RestController.class)
                    .should(haveAllInstanceFieldsFinal());

    // ──────────────────────────────────────────────────────────────────
    // 예외 처리 규칙 (Req 5.3): cause 체이닝 검증
    // ──────────────────────────────────────────────────────────────────

    /**
     * Checked exception을 catch한 후 DomainException 계열을 생성할 때 cause를 포함해야 한다.
     *
     * <p>바이트코드 수준의 heuristic 검사: catch 블록에서 checked exception을 잡는 코드 유닛이
     * DomainException 서브타입의 1-arg 생성자(code)만 호출하고 2-arg 생성자(code, cause)를
     * 호출하지 않으면 위반으로 판정한다.
     *
     * <p>한계: catch 블록 경계와 생성자 호출의 정확한 연관은 바이트코드만으론 완벽히 파악 불가.
     * 코드 리뷰로 보완한다.
     */
    @ArchTest
    static final ArchRule catch_blocks_should_chain_cause_when_rethrowing =
            classes().that().areAnnotatedWith(Service.class)
                    .or().areAnnotatedWith(Component.class)
                    .should(chainCauseWhenCatchingCheckedExceptions());

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

    private static ArchCondition<JavaClass> notContainTryCatchBlocks() {
        return new ArchCondition<>("not contain try-catch blocks") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    Set<TryCatchBlock> tryCatchBlocks = codeUnit.getTryCatchBlocks();
                    for (TryCatchBlock block : tryCatchBlocks) {
                        Set<JavaClass> caughtTypes = block.getCaughtThrowables();
                        // 컴파일러가 생성하는 finally 블록(caughtThrowables 비어있음)은 제외
                        if (caughtTypes.isEmpty()) {
                            continue;
                        }
                        // Throwable만 잡는 catch-all도 컴파일러 생성 finally일 가능성이 높아 제외
                        boolean isOnlyThrowable = caughtTypes.size() == 1
                                && caughtTypes.iterator().next().getName().equals("java.lang.Throwable");
                        if (isOnlyThrowable) {
                            continue;
                        }
                        String message = String.format(
                                ArchRuleConstants.CONTROLLER_TRY_CATCH_VIOLATION,
                                codeUnit.getName(), javaClass.getName(),
                                caughtTypes.stream().map(JavaClass::getSimpleName).toList());
                        events.add(SimpleConditionEvent.violated(javaClass, message));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAllInstanceFieldsFinal() {
        return new ArchCondition<>("have all instance fields declared as final") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                // @Entity, @Configuration, @MappedSuperclass는 제외
                if (isExcludedFromFinalFieldRule(javaClass)) {
                    return;
                }
                for (JavaField field : javaClass.getFields()) {
                    // static 필드 제외
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    // synthetic 필드 제외 (Lombok 등 컴파일러 생성)
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

            private boolean isExcludedFromFinalFieldRule(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(Entity.class)
                        || javaClass.isAnnotatedWith(MappedSuperclass.class)
                        || javaClass.isAnnotatedWith(Configuration.class)
                        || javaClass.isAssignableTo(SmartLifecycle.class)
                        || javaClass.isAssignableTo(Lifecycle.class);
            }
        };
    }

    private static ArchCondition<JavaClass> chainCauseWhenCatchingCheckedExceptions() {
        return new ArchCondition<>("chain cause when catching checked exceptions and rethrowing") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                        Set<JavaClass> caughtTypes = block.getCaughtThrowables();
                        if (caughtTypes.isEmpty()) {
                            continue;
                        }
                        // checked exception을 catch하는 블록만 검사
                        boolean catchesCheckedException = caughtTypes.stream()
                                .anyMatch(ArchitectureRulesTest::isCheckedException);
                        if (!catchesCheckedException) {
                            continue;
                        }
                        // 해당 코드유닛이 DomainException 서브타입 생성자를 호출하는지 확인
                        boolean createsDomainException = codeUnit.getConstructorCallsFromSelf().stream()
                                .anyMatch(call -> isDomainExceptionSubtype(call.getTargetOwner()));
                        if (!createsDomainException) {
                            continue; // catch 후 DomainException을 생성하지 않으면 무관
                        }
                        // cause 체이닝 여부: 2-arg 이상 생성자(code, cause)를 호출하면 pass
                        boolean usesCauseChaining = codeUnit.getConstructorCallsFromSelf().stream()
                                .filter(call -> isDomainExceptionSubtype(call.getTargetOwner()))
                                .anyMatch(call -> call.getTarget().getRawParameterTypes().size() >= 2);
                        if (!usesCauseChaining) {
                            String message = String.format(
                                    ArchRuleConstants.CAUSE_CHAINING_VIOLATION,
                                    codeUnit.getName(), javaClass.getName(),
                                    caughtTypes.stream().map(JavaClass::getSimpleName).toList());
                            events.add(SimpleConditionEvent.violated(javaClass, message));
                        }
                    }
                }
            }
        };
    }

    private static boolean isCheckedException(JavaClass exceptionClass) {
        // RuntimeException과 Error의 서브타입이 아니면 checked exception
        return exceptionClass.isAssignableTo(Exception.class)
                && !exceptionClass.isAssignableTo(RuntimeException.class);
    }

    private static boolean isDomainExceptionSubtype(JavaClass javaClass) {
        return javaClass.isAssignableTo(DomainException.class);
    }
}

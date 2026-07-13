package io.wedocs.doc.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// UTF-8 "바이트" 길이 상한 — `@Size`는 문자 수(UTF-16 code unit) 기준이라
/// bcrypt의 72"바이트" 한계 같은 바이트 계약을 검증하지 못한다(한글 3바이트/자).
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "must be at most {value} bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

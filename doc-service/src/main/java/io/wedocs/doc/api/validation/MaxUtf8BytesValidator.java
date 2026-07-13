package io.wedocs.doc.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
        this.maxBytes = annotation.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        // null 허용 여부는 @NotBlank 소관 — 검증기 표준 관례.
        return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}

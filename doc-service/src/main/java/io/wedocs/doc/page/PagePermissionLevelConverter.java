package io.wedocs.doc.page;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/// level 컬럼은 소문자로 저장(PRD/DDL 일관성). CHECK 제약(in ('editor','viewer'))과 정합.
@Converter
public class PagePermissionLevelConverter implements AttributeConverter<PagePermissionLevel, String> {

    @Override
    public String convertToDatabaseColumn(PagePermissionLevel level) {
        return level == null ? null : level.name().toLowerCase();
    }

    @Override
    public PagePermissionLevel convertToEntityAttribute(String value) {
        return value == null ? null : PagePermissionLevel.valueOf(value.toUpperCase());
    }
}

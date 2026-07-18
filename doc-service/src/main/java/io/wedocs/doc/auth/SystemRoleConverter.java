package io.wedocs.doc.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/// system_role 컬럼은 소문자로 저장(PRD/DDL 일관성: owner/member/editor/viewer와 동일 규약).
/// enum 상수는 Java 관례상 대문자 → DB 경계에서 변환. CHECK 제약(`in ('user','system_admin')`)과 정합.
@Converter
public class SystemRoleConverter implements AttributeConverter<SystemRole, String> {

    @Override
    public String convertToDatabaseColumn(SystemRole role) {
        return role == null ? null : role.wireValue();
    }

    @Override
    public SystemRole convertToEntityAttribute(String value) {
        return value == null ? null : SystemRole.valueOf(value.toUpperCase());
    }
}

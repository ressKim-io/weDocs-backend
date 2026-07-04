package io.wedocs.doc.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/// role 컬럼은 소문자로 저장(PRD/DDL 일관성: SystemRoleConverter와 동일 규약). CHECK 제약(in ('owner','member'))과 정합.
@Converter
public class WorkspaceRoleConverter implements AttributeConverter<WorkspaceRole, String> {

    @Override
    public String convertToDatabaseColumn(WorkspaceRole role) {
        return role == null ? null : role.name().toLowerCase();
    }

    @Override
    public WorkspaceRole convertToEntityAttribute(String value) {
        return value == null ? null : WorkspaceRole.valueOf(value.toUpperCase());
    }
}

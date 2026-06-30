package io.wedocs.doc.domain;

/// 전역 운영 역할 (ADR-0016). 워크스페이스 역할(owner/member·editor/viewer)과 직교 — 사람 단위 단일.
/// DB 저장값은 소문자(SystemRoleConverter) — role/level 컬럼과 동일 규약.
public enum SystemRole {
    USER,
    SYSTEM_ADMIN
}

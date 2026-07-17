package io.wedocs.doc.service;

/// 이미 멤버인 사용자를 재초대 → HTTP 409.
public class DuplicateMemberException extends ConflictException {

    private static final String MESSAGE = "already a workspace member";

    public DuplicateMemberException() {
        super(MESSAGE);
    }

    /// 복합 PK 위반(동시 초대 레이스)에서 원인 체인 보존용(error-handling P4).
    public DuplicateMemberException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

package io.wedocs.doc.common.error;

import io.grpc.Status;
import org.springframework.http.HttpStatus;

/// doc-service 도메인 실패 전체 집합 — 서비스별 에러 카탈로그 SSOT (error-handling.md P7, ADR-0018).
/// slug=wire 식별자(kebab), message=고정 클라이언트 문구(id·내부 상태 보간 금지, secure-coding P4),
/// category=HTTP 매핑 겸 도메인 예외 타입, grpc=전송 코드. HTTP/gRPC 매핑은 이 필드에서만 읽는다
/// (advice·어댑터의 정상 매핑 경로에 상태코드 하드코딩 금지).
public enum DocErrorCode {
    PAGE_NOT_FOUND(Category.NOT_FOUND, "page-not-found", "page not found", Status.Code.NOT_FOUND),
    WORKSPACE_NOT_FOUND(Category.NOT_FOUND, "workspace-not-found", "workspace not found", Status.Code.NOT_FOUND),
    USER_NOT_FOUND(Category.NOT_FOUND, "user-not-found", "user not found", Status.Code.NOT_FOUND),
    EMAIL_ALREADY_USED(Category.CONFLICT, "email-already-used", "email already in use", Status.Code.ALREADY_EXISTS),
    DUPLICATE_MEMBER(Category.CONFLICT, "duplicate-member", "already a workspace member", Status.Code.ALREADY_EXISTS),
    PAGE_CYCLE(Category.CONFLICT, "page-cycle", "page move would create a cycle", Status.Code.FAILED_PRECONDITION),
    PAGE_DEPTH_CAP_EXCEEDED(
            Category.CONFLICT, "page-depth-cap-exceeded", "page tree depth cap exceeded", Status.Code.FAILED_PRECONDITION),
    CROSS_WORKSPACE_PARENT(
            Category.CONFLICT, "cross-workspace-parent", "parent page belongs to a different workspace",
            Status.Code.FAILED_PRECONDITION),
    INSUFFICIENT_PERMISSION(
            Category.FORBIDDEN, "insufficient-permission", "insufficient permission", Status.Code.PERMISSION_DENIED),
    INVALID_CREDENTIALS(Category.UNAUTHORIZED, "invalid-credentials", "invalid credentials", Status.Code.UNAUTHENTICATED),
    INVARIANT_BROKEN(Category.INVARIANT, "invariant-broken", "unexpected error", Status.Code.INTERNAL);

    private final Category category;
    private final String slug;
    private final String message;
    private final Status.Code grpc;

    DocErrorCode(Category category, String slug, String message, Status.Code grpc) {
        this.category = category;
        this.slug = slug;
        this.message = message;
        this.grpc = grpc;
    }

    public Category category() {
        return category;
    }

    public String slug() {
        return slug;
    }

    public String message() {
        return message;
    }

    public HttpStatus http() {
        return category.http;
    }

    public Status.Code grpc() {
        return grpc;
    }

    /// 카테고리 = 도메인 예외 타입 ↔ HTTP 상태의 1:1 대응. 카테고리 예외 생성자가 이 값으로
    /// 코드-타입 정합을 강제한다(NotFoundException에 CONFLICT 코드를 실을 수 없게 — illegal state 방지).
    public enum Category {
        NOT_FOUND(HttpStatus.NOT_FOUND),
        CONFLICT(HttpStatus.CONFLICT),
        FORBIDDEN(HttpStatus.FORBIDDEN),
        UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
        INVARIANT(HttpStatus.INTERNAL_SERVER_ERROR);

        private final HttpStatus http;

        Category(HttpStatus http) {
            this.http = http;
        }
    }
}

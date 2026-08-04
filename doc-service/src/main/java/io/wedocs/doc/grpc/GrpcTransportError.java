package io.wedocs.doc.grpc;

import io.grpc.Status;

/// gRPC 전송 계층의 클라이언트 대면 고정 에러 — DocErrorCode 카탈로그 외부의 전송 에러만.
/// GrpcOps가 Status.withDescription()에 전달하는 문자열의 SSOT.
/// DocErrorCode.message()가 이미 관리하는 도메인 에러(handleDomainError 경로)는 여기 포함하지 않는다.
public enum GrpcTransportError {

    /// UUID 파싱 실패 (INVALID_ARGUMENT).
    MALFORMED_ID("malformed id", Status.Code.INVALID_ARGUMENT),

    /// 예상치 못한 서버 내부 에러 — 내부 상세를 숨기기 위한 불투명 메시지 (secure-coding P4).
    INTERNAL_ERROR("internal error", Status.Code.INTERNAL);

    private final String description;
    private final Status.Code code;

    GrpcTransportError(String description, Status.Code code) {
        this.description = description;
        this.code = code;
    }

    public String description() {
        return description;
    }

    public Status.Code code() {
        return code;
    }

    /// gRPC Status로 변환하여 RuntimeException 생성 — GrpcOps에서 observer.onError()에 직접 전달.
    public io.grpc.StatusRuntimeException toStatusException() {
        return Status.fromCode(code).withDescription(description).asRuntimeException();
    }
}

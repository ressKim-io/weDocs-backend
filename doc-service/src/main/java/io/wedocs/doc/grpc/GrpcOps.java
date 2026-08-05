package io.wedocs.doc.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.DomainException;
import io.wedocs.doc.common.logging.DocLogErrorType;
import io.wedocs.doc.common.logging.DocLogEvent;
import io.wedocs.doc.common.logging.LogEvents;
import io.wedocs.doc.common.logging.LogFields;
import lombok.extern.slf4j.Slf4j;

import static io.wedocs.doc.grpc.GrpcTransportError.INTERNAL_ERROR;
import static io.wedocs.doc.grpc.GrpcTransportError.MALFORMED_ID;

import java.util.UUID;

/// gRPC RPC 구현의 공통 보일러플레이트(UUID 파싱, 에러 매핑)를 한 곳에 모은다.
/// 각 RPC 메서드는 이 유틸리티를 사용해 반복 코드를 제거하고 비즈니스 로직에 집중할 수 있다.
@Slf4j
public final class GrpcOps {

    private GrpcOps() {}

    /// UUID 파싱 실패 시 INVALID_ARGUMENT를 observer에 전달하고 null을 반환한다.
    /// 호출자는 null 반환 시 즉시 리턴해야 한다(응답은 이미 전송됨).
    /// 원시 입력값은 클라이언트 통제 값이라 로그에 길이만 남긴다(secure-coding P1/P4).
    /// 구조화 이벤트: `grpc_call_rejected`(error.type=malformed-id, wedocs.request.id_length).
    public static UUID parseUuid(String raw, StreamObserver<?> observer) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            LogEvents.event(log, DocLogEvent.GRPC_CALL_REJECTED)
                    .errorType(DocLogErrorType.MALFORMED_ID)
                    .attr(LogFields.REQUEST_ID_LENGTH, raw.length())
                    .cause(e)
                    .log();
            observer.onError(MALFORMED_ID.toStatusException());
            return null;
        }
    }

    /// DomainException → gRPC Status 변환 공통 로직.
    /// 내부 불변식 위반(isInternal)은 INTERNAL로 매핑하고 상세를 서버 로그에만 남긴다(P4).
    /// 클라이언트 대면 에러는 카탈로그의 grpc 코드와 message를 그대로 사용한다.
    /// 구조화 이벤트: 내부 → `grpc_call_failed`, 비내부 → `grpc_call_rejected`.
    public static void handleDomainError(StreamObserver<?> observer, String rpcName, DomainException e) {
        DocErrorCode code = e.code();
        if (code.isInternal()) {
            LogEvents.event(log, DocLogEvent.GRPC_CALL_FAILED)
                    .errorType(code.slug())
                    .attr(LogFields.RPC_METHOD, rpcName)
                    .cause(e)
                    .log();
            observer.onError(INTERNAL_ERROR.toStatusException());
            return;
        }
        LogEvents.event(log, DocLogEvent.GRPC_CALL_REJECTED)
                .errorType(code.slug())
                .attr(LogFields.RPC_METHOD, rpcName)
                .log();
        observer.onError(
                Status.fromCode(code.grpc()).withDescription(code.message()).asRuntimeException());
    }

    /// 예상치 못한 RuntimeException → gRPC INTERNAL 매핑.
    /// 내부 상세는 서버 로그에만 남기고 클라이언트에는 고정 "internal error" 메시지만 전달한다(P4).
    /// 구조화 이벤트: `grpc_call_failed`(error.type=unexpected-internal-error).
    public static void handleInternalError(StreamObserver<?> observer, String rpcName, RuntimeException cause) {
        LogEvents.event(log, DocLogEvent.GRPC_CALL_FAILED)
                .errorType(DocLogErrorType.UNEXPECTED_INTERNAL_ERROR)
                .attr(LogFields.RPC_METHOD, rpcName)
                .cause(cause)
                .log();
        observer.onError(INTERNAL_ERROR.toStatusException());
    }
}

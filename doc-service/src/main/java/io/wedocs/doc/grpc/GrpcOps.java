package io.wedocs.doc.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.DomainException;
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
    public static UUID parseUuid(String raw, StreamObserver<?> observer) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("malformed id in gRPC request, length={}", raw.length(), e);
            observer.onError(MALFORMED_ID.toStatusException());
            return null;
        }
    }

    /// DomainException → gRPC Status 변환 공통 로직.
    /// 내부 불변식 위반(isInternal)은 INTERNAL로 매핑하고 상세를 서버 로그에만 남긴다(P4).
    /// 클라이언트 대면 에러는 카탈로그의 grpc 코드와 message를 그대로 사용한다.
    public static void handleDomainError(StreamObserver<?> observer, String rpcName, DomainException e) {
        DocErrorCode code = e.code();
        if (code.isInternal()) {
            log.error("{}: domain invariant broken code={}", rpcName, code.slug(), e);
            observer.onError(INTERNAL_ERROR.toStatusException());
            return;
        }
        log.warn("{}: {} ({})", rpcName, code.slug(), code.grpc());
        observer.onError(
                Status.fromCode(code.grpc()).withDescription(code.message()).asRuntimeException());
    }

    /// 예상치 못한 RuntimeException → gRPC INTERNAL 매핑.
    /// 내부 상세는 서버 로그에만 남기고 클라이언트에는 고정 "internal error" 메시지만 전달한다(P4).
    public static void handleInternalError(StreamObserver<?> observer, String rpcName, RuntimeException cause) {
        log.error("{}: unexpected internal error", rpcName, cause);
        observer.onError(INTERNAL_ERROR.toStatusException());
    }
}

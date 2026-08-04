package io.wedocs.doc.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.InvariantViolationException;
import io.wedocs.doc.common.error.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/// GrpcOps 유틸리티의 순수 단위 테스트 — UUID 파싱, 도메인 에러 매핑, 내부 에러 매핑을 검증한다.
@ExtendWith(MockitoExtension.class)
class GrpcOpsTest {

    @Mock
    private StreamObserver<?> observer;

    @Nested
    @DisplayName("parseUuid")
    class ParseUuid {

        @Test
        @DisplayName("유효한 UUID 문자열 → 파싱된 UUID 반환, observer 호출 없음")
        void validUuid_returnsParsedUuid() {
            // Given
            UUID expected = UUID.randomUUID();
            String raw = expected.toString();

            // When
            UUID result = GrpcOps.parseUuid(raw, observer);

            // Then
            assertThat(result).isEqualTo(expected);
            verifyNoInteractions(observer);
        }

        @Test
        @DisplayName("잘못된 UUID 문자열 → null 반환, INVALID_ARGUMENT 에러 전달")
        void invalidUuid_returnsNullAndSendsError() {
            // Given
            String raw = "not-a-uuid";

            // When
            UUID result = GrpcOps.parseUuid(raw, observer);

            // Then
            assertThat(result).isNull();
            ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(captor.capture());

            StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
            // 동작 보존 증거: 리터럴 단언
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(sre.getStatus().getDescription()).isEqualTo("malformed id");
            // enum 기준 단언
            assertThat(sre.getStatus().getDescription()).isEqualTo(GrpcTransportError.MALFORMED_ID.description());
            assertThat(sre.getStatus().getCode()).isEqualTo(GrpcTransportError.MALFORMED_ID.code());
        }

        @Test
        @DisplayName("빈 문자열 → null 반환, INVALID_ARGUMENT 에러 전달")
        void emptyString_returnsNullAndSendsError() {
            // Given
            String raw = "";

            // When
            UUID result = GrpcOps.parseUuid(raw, observer);

            // Then
            assertThat(result).isNull();
            ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(captor.capture());

            StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
            // 동작 보존 증거: 리터럴 단언
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(sre.getStatus().getDescription()).isEqualTo("malformed id");
            // enum 기준 단언
            assertThat(sre.getStatus().getDescription()).isEqualTo(GrpcTransportError.MALFORMED_ID.description());
            assertThat(sre.getStatus().getCode()).isEqualTo(GrpcTransportError.MALFORMED_ID.code());
        }
    }

    @Nested
    @DisplayName("handleDomainError")
    class HandleDomainError {

        @Test
        @DisplayName("내부 에러코드(INVARIANT) → INTERNAL 상태, 고정 'internal error' 메시지")
        void internalErrorCode_mapsToInternal() {
            // Given
            InvariantViolationException ex = new InvariantViolationException("FK 결손: page_id=abc");

            // When
            GrpcOps.handleDomainError(observer, "TestRpc", ex);

            // Then
            ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(captor.capture());

            StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
            // 동작 보존 증거: 리터럴 단언
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(sre.getStatus().getDescription()).isEqualTo("internal error");
            // 내부 상세가 클라이언트에 노출되지 않음
            assertThat(sre.getStatus().getDescription()).doesNotContain("FK 결손", "page_id");
            // enum 기준 단언
            assertThat(sre.getStatus().getDescription()).isEqualTo(GrpcTransportError.INTERNAL_ERROR.description());
            assertThat(sre.getStatus().getCode()).isEqualTo(GrpcTransportError.INTERNAL_ERROR.code());
        }

        @Test
        @DisplayName("클라이언트 대면 에러코드(NOT_FOUND) → 카탈로그의 gRPC 코드와 메시지 사용")
        void clientFacingErrorCode_mapsToGrpcCodeAndMessage() {
            // Given
            NotFoundException ex = new NotFoundException(DocErrorCode.PAGE_NOT_FOUND);

            // When
            GrpcOps.handleDomainError(observer, "GetPage", ex);

            // Then
            ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(captor.capture());

            StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(sre.getStatus().getDescription()).isEqualTo("page not found");
        }
    }

    @Nested
    @DisplayName("handleInternalError")
    class HandleInternalError {

        @Test
        @DisplayName("RuntimeException → INTERNAL 상태, 고정 'internal error' 메시지, 내부 상세 미노출")
        void runtimeException_mapsToInternalWithFixedMessage() {
            // Given
            RuntimeException cause = new RuntimeException("NPE in serializer: field=title");

            // When
            GrpcOps.handleInternalError(observer, "SaveDoc", cause);

            // Then
            ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(captor.capture());

            StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(sre.getStatus().getDescription()).isEqualTo("internal error");
            // 내부 상세가 클라이언트에 노출되지 않음
            assertThat(sre.getStatus().getDescription()).doesNotContain("NPE", "serializer");
            // enum 기준 단언
            assertThat(sre.getStatus().getDescription()).isEqualTo(GrpcTransportError.INTERNAL_ERROR.description());
            assertThat(sre.getStatus().getCode()).isEqualTo(GrpcTransportError.INTERNAL_ERROR.code());
        }
    }
}

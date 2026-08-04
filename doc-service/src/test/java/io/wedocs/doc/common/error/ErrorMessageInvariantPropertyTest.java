package io.wedocs.doc.common.error;

import io.wedocs.doc.grpc.GrpcTransportError;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 6.4**
///
/// Property 4: enum message() 접근자 비null 불변식 —
/// GrpcTransportError.description()과 InfraErrorCode.message()가 null이 아니고 공백이 아님을 보장한다.
@Tag("Feature: error-message-centralization")
@Tag("Property 4: 메시지 비null 불변식")
class ErrorMessageInvariantPropertyTest {

    @Property(tries = 100)
    void grpcTransportError_description_isNeverNullOrBlank(@ForAll GrpcTransportError error) {
        assertThat(error.description()).isNotNull().isNotBlank();
    }

    @Property(tries = 100)
    void infraErrorCode_message_isNeverNullOrBlank(@ForAll InfraErrorCode error) {
        assertThat(error.message()).isNotNull().isNotBlank();
    }
}

package io.wedocs.doc.grpc;

import io.grpc.StatusRuntimeException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: GrpcTransportError의 gRPC Status 변환 일관성.
 *
 * <p>모든 GrpcTransportError enum 엔트리에 대해 {@code toStatusException()}이 반환하는
 * StatusRuntimeException의 description과 status code가 enum 필드와 일치하는지 검증한다.
 *
 * <p><b>Validates: Requirements 1.1, 1.2</b>
 */
@Tag("Feature: error-message-centralization")
@Tag("Property 1: gRPC Status 변환 일관성")
class GrpcTransportErrorPropertyTest {

    @Property(tries = 100)
    void toStatusException_preserves_description_and_code(@ForAll GrpcTransportError error) {
        StatusRuntimeException ex = error.toStatusException();

        assertThat(ex.getStatus().getDescription())
                .as("description of %s", error.name())
                .isEqualTo(error.description());

        assertThat(ex.getStatus().getCode())
                .as("status code of %s", error.name())
                .isEqualTo(error.code());
    }
}

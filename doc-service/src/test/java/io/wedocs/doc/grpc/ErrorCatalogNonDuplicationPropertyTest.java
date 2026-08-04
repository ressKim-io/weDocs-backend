package io.wedocs.doc.grpc;

import io.wedocs.doc.common.error.DocErrorCode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// Property 3: DocErrorCode와의 비중복 불변식.
/// DocErrorCode.message()와 GrpcTransportError.description()이 동일한 쌍이 없음을 단언한다.
/// GrpcTransportError는 DocErrorCode 카탈로그 외부의 전송 계층 고정 에러만 관리해야 한다.
///
/// **Validates: Requirements 6.1, 6.3**
@Tag("Feature: error-message-centralization")
@Tag("Property 3: DocErrorCode 비중복")
class ErrorCatalogNonDuplicationPropertyTest {

    @Property(tries = 100)
    void noDocErrorCodeMessageDuplicatesGrpcTransportErrorDescription(
            @ForAll DocErrorCode docError,
            @ForAll GrpcTransportError transportError) {

        assertThat(docError.message())
                .as("DocErrorCode.%s.message() must not equal GrpcTransportError.%s.description() "
                        + "— domain error messages must not be duplicated in the transport layer enum",
                        docError.name(), transportError.name())
                .isNotEqualTo(transportError.description());
    }
}

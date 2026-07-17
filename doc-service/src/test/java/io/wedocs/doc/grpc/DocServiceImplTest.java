package io.wedocs.doc.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.InvariantViolationException;
import io.wedocs.doc.service.DocMetaService;
import io.wedocs.doc.service.PermissionService;
import io.wedocs.doc.service.SnapshotService;
import io.wedocs.proto.common.DocRef;
import io.wedocs.proto.doc.DocMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// gRPC 경계의 에러 매핑 순수 단위 검증 — 특히 불변식(INTERNAL)이 내부 상세를 description으로 흘리지
/// 않음(secure-coding P4). InProcess 서버(DocServiceGrpcIntegrationTest)와 달리 FK 결손처럼
/// 실 DB로 재현 불가한 경로를 mock으로 강제한다.
@ExtendWith(MockitoExtension.class)
class DocServiceImplTest {

    @Mock private PermissionService permissionService;
    @Mock private SnapshotService snapshotService;
    @Mock private DocMetaService docMetaService;
    @Mock private StreamObserver<DocMeta> observer;

    @Test
    @DisplayName("GetDocMeta 불변식 위반 — INTERNAL + 고정 'internal error', 내부 상세는 description에 없음")
    void getDocMeta_invariantBroken_hidesLogDetail() {
        // Given: 정상 경로 도달 불가한 FK 결손을 mock으로 강제
        DocServiceImpl service = new DocServiceImpl(permissionService, snapshotService, docMetaService);
        String internal = "workspace missing for page (FK 불변식 위반): " + UUID.randomUUID();
        when(docMetaService.getMeta(any())).thenThrow(new InvariantViolationException(internal));

        // When
        service.getDocMeta(DocRef.newBuilder().setDocId(UUID.randomUUID().toString()).build(), observer);

        // Then: onError(INTERNAL), description은 고정 문구 — logDetail 미노출
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        Status status = Status.fromThrowable(error.getValue());
        assertThat(error.getValue()).isInstanceOf(StatusRuntimeException.class);
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("internal error");
        assertThat(status.getDescription()).doesNotContain("workspace missing", "불변식");
    }
}

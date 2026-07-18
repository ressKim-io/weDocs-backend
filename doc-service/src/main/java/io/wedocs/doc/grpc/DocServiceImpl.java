package io.wedocs.doc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.DocErrorCode;
import io.wedocs.doc.common.error.DomainException;
import io.wedocs.doc.page.DocMetaService;
import io.wedocs.doc.page.DocMetaService.DocMetaView;
import io.wedocs.doc.page.EffectivePermission;
import io.wedocs.doc.page.PermissionService;
import io.wedocs.doc.snapshot.SnapshotService;
import io.wedocs.doc.snapshot.SnapshotService.SnapshotView;
import io.wedocs.proto.common.DocRef;
import io.wedocs.proto.common.Role;
import io.wedocs.proto.doc.CheckPermissionRequest;
import io.wedocs.proto.doc.CheckPermissionResponse;
import io.wedocs.proto.doc.DocMeta;
import io.wedocs.proto.doc.DocServiceGrpc;
import io.wedocs.proto.doc.LoadSnapshotRequest;
import io.wedocs.proto.doc.LoadSnapshotResponse;
import io.wedocs.proto.doc.SaveSnapshotRequest;
import io.wedocs.proto.doc.SaveSnapshotResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/// DocService gRPC 서버 구현(M2 1b). 4 RPC 전부 내부 전용 — 신뢰 경계(mTLS/NetworkPolicy 전제, M5)는
/// 인프라 레이어가 강제하고 여기서는 문서로만 남긴다(secure-coding.md P3).
/// CheckPermission 자체가 "인가 배선"의 구현체(ADR-0014) — SaveSnapshot/LoadSnapshot/GetDocMeta는
/// gateway/엔진의 CheckPermission 호출을 전제하는 내부 전용 RPC.
@Slf4j
@RequiredArgsConstructor
@Component
public class DocServiceImpl extends DocServiceGrpc.DocServiceImplBase {

    private final PermissionService permissionService;
    private final SnapshotService snapshotService;
    private final DocMetaService docMetaService;

    @Override
    public void checkPermission(
            CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> responseObserver) {
        UUID pageId = parseUuidOrFail(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }
        UUID userId = parseUuidOrFail(request.getUserId(), responseObserver);
        if (userId == null) {
            return;
        }

        try {
            EffectivePermission result = permissionService.resolve(pageId, userId);
            responseObserver.onNext(CheckPermissionResponse.newBuilder()
                    .setAllowed(result.allowed())
                    .setRole(toProtoRole(result.role()))
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            failInternal(responseObserver, "CheckPermission", e);
        }
    }

    @Override
    public void saveSnapshot(SaveSnapshotRequest request, StreamObserver<SaveSnapshotResponse> responseObserver) {
        UUID pageId = parseUuidOrFail(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }

        try {
            long version = snapshotService.save(pageId, request.getSnapshot().toByteArray(), request.getVersion());
            responseObserver.onNext(SaveSnapshotResponse.newBuilder().setVersion(version).build());
            responseObserver.onCompleted();
        } catch (DomainException e) {
            failDomain(responseObserver, "SaveSnapshot", e);
        } catch (RuntimeException e) {
            failInternal(responseObserver, "SaveSnapshot", e);
        }
    }

    @Override
    public void loadSnapshot(LoadSnapshotRequest request, StreamObserver<LoadSnapshotResponse> responseObserver) {
        UUID pageId = parseUuidOrFail(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }

        try {
            SnapshotView view = snapshotService.load(pageId);
            responseObserver.onNext(LoadSnapshotResponse.newBuilder()
                    .setSnapshot(ByteString.copyFrom(view.snapshot()))
                    .setVersion(view.version())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            failInternal(responseObserver, "LoadSnapshot", e);
        }
    }

    @Override
    public void getDocMeta(DocRef request, StreamObserver<DocMeta> responseObserver) {
        UUID pageId = parseUuidOrFail(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }

        try {
            DocMetaView view = docMetaService.getMeta(pageId);
            responseObserver.onNext(DocMeta.newBuilder()
                    .setDocId(view.docId().toString())
                    .setTitle(view.title())
                    .setOwnerId(view.ownerId().toString())
                    .setCreatedAt(view.createdAt().toEpochMilli())
                    .setUpdatedAt(view.updatedAt().toEpochMilli())
                    .setWorkspaceId(view.workspaceId().toString())
                    .setParentId(view.parentId() == null ? "" : view.parentId().toString())
                    .build());
            responseObserver.onCompleted();
        } catch (DomainException e) {
            // 미존재(NOT_FOUND) + FK 불변식 위반(INVARIANT_BROKEN→INTERNAL, 정상 경로 도달 불가)
            // 모두 카탈로그 코드의 gRPC 매핑으로 처리 — 내부 상세는 로그로만, 클라이언트엔 분류만(P4).
            failDomain(responseObserver, "GetDocMeta", e);
        } catch (RuntimeException e) {
            failInternal(responseObserver, "GetDocMeta", e);
        }
    }

    /// UUID.fromString의 원본 예외(원시 입력값 포함)를 캐치해 안전한 메시지로 재포장 — 클라이언트에는
    /// 분류(code)만, 원인은 서버 로그에만(secure-coding.md P1/P4). gRPC 메타데이터·필드도 클라이언트
    /// 통제 값이라 신뢰하지 않는다.
    private static UUID parseUuidOrFail(String raw, StreamObserver<?> responseObserver) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("malformed id in DocService request, length={}", raw.length(), e);
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("malformed id").asRuntimeException());
            return null;
        }
    }

    /// 도메인 실패 → gRPC Status 매핑 한 곳(error-handling P1/P7) — 코드·description을 카탈로그에서 읽는다.
    /// 5xx(불변식)는 내부 상세를 로그로만 남기고 클라이언트엔 고정 "internal error"(P4).
    private static void failDomain(StreamObserver<?> responseObserver, String rpcName, DomainException e) {
        DocErrorCode code = e.code();
        if (code.isInternal()) {
            log.error("{}: domain invariant broken code={}", rpcName, code.slug(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
            return;
        }
        log.warn("{}: {} ({})", rpcName, code.slug(), code.grpc());
        responseObserver.onError(
                Status.fromCode(code.grpc()).withDescription(code.message()).asRuntimeException());
    }

    private static void failInternal(StreamObserver<?> responseObserver, String rpcName, RuntimeException cause) {
        log.error("{}: unexpected internal error", rpcName, cause);
        responseObserver.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
    }

    private static Role toProtoRole(EffectivePermission.EffectiveRole role) {
        return switch (role) {
            case NONE -> Role.ROLE_UNSPECIFIED;
            case VIEWER -> Role.ROLE_VIEWER;
            case EDITOR -> Role.ROLE_EDITOR;
            case OWNER -> Role.ROLE_OWNER;
        };
    }
}

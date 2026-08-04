package io.wedocs.doc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import io.wedocs.doc.common.error.DomainException;
import io.wedocs.doc.page.DocMetaService;
import io.wedocs.doc.page.DocMetaService.DocMetaView;
import io.wedocs.doc.page.EffectivePermission;
import io.wedocs.doc.page.PermissionService;
import io.wedocs.doc.snapshot.SnapshotService;
import io.wedocs.doc.snapshot.SnapshotService.SnapshotView;
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
        UUID pageId = GrpcOps.parseUuid(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }
        UUID userId = GrpcOps.parseUuid(request.getUserId(), responseObserver);
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
            GrpcOps.handleInternalError(responseObserver, "CheckPermission", e);
        }
    }

    @Override
    public void saveSnapshot(SaveSnapshotRequest request, StreamObserver<SaveSnapshotResponse> responseObserver) {
        UUID pageId = GrpcOps.parseUuid(request.getDocId(), responseObserver);
        if (pageId == null) {
            return;
        }

        try {
            long version = snapshotService.save(pageId, request.getSnapshot().toByteArray(), request.getVersion());
            responseObserver.onNext(SaveSnapshotResponse.newBuilder().setVersion(version).build());
            responseObserver.onCompleted();
        } catch (DomainException e) {
            GrpcOps.handleDomainError(responseObserver, "SaveSnapshot", e);
        } catch (RuntimeException e) {
            GrpcOps.handleInternalError(responseObserver, "SaveSnapshot", e);
        }
    }

    @Override
    public void loadSnapshot(LoadSnapshotRequest request, StreamObserver<LoadSnapshotResponse> responseObserver) {
        UUID pageId = GrpcOps.parseUuid(request.getDocId(), responseObserver);
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
            GrpcOps.handleInternalError(responseObserver, "LoadSnapshot", e);
        }
    }

    @Override
    public void getDocMeta(io.wedocs.proto.common.DocRef request, StreamObserver<DocMeta> responseObserver) {
        UUID pageId = GrpcOps.parseUuid(request.getDocId(), responseObserver);
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
            GrpcOps.handleDomainError(responseObserver, "GetDocMeta", e);
        } catch (RuntimeException e) {
            GrpcOps.handleInternalError(responseObserver, "GetDocMeta", e);
        }
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

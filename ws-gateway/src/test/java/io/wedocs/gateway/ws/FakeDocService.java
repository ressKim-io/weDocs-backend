package io.wedocs.gateway.ws;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.wedocs.proto.common.Role;
import io.wedocs.proto.doc.CheckPermissionRequest;
import io.wedocs.proto.doc.CheckPermissionResponse;
import io.wedocs.proto.doc.DocServiceGrpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// doc-service 대역(인가 권위). 핸드셰이크 인가 경로를 실제 gRPC 왕복으로 검증하기 위해 랜덤 포트에
/// 실 TCP 서버로 뜬다 — ws-gateway 테스트 클래스패스엔 `grpc-inprocess`가 없고, 엔진 대역(FakeCrdtEngine)도
/// 같은 방식이라 패턴을 맞춘다.
final class FakeDocService extends DocServiceGrpc.DocServiceImplBase {

    /// 다음 CheckPermission 응답. 테스트가 시나리오별로 갈아끼운다.
    private final AtomicReference<Behavior> behavior = new AtomicReference<>(Behavior.allow(Role.ROLE_EDITOR));

    final BlockingQueue<CheckPermissionRequest> requests = new LinkedBlockingQueue<>();

    private Server server;

    /// 응답 시나리오. 지연(delay)은 deadline 초과를 결정적으로 재현하기 위한 것.
    record Behavior(boolean allowed, Role role, Status error, long delayMillis) {

        static Behavior allow(Role role) {
            return new Behavior(true, role, null, 0);
        }

        static Behavior deny() {
            return new Behavior(false, Role.ROLE_UNSPECIFIED, null, 0);
        }

        static Behavior error(Status status) {
            return new Behavior(false, Role.ROLE_UNSPECIFIED, status, 0);
        }

        static Behavior slow(long delayMillis) {
            return new Behavior(true, Role.ROLE_EDITOR, null, delayMillis);
        }
    }

    void behave(Behavior next) {
        behavior.set(next);
    }

    void reset() {
        behavior.set(Behavior.allow(Role.ROLE_EDITOR));
        requests.clear();
    }

    int startOnRandomPort() {
        try {
            server = ServerBuilder.forPort(0).addService(this).build().start();
            return server.getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void stop() {
        if (server != null) {
            server.shutdownNow();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void checkPermission(
            CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> responseObserver) {
        requests.add(request);
        Behavior current = behavior.get();
        if (current.delayMillis() > 0) {
            try {
                Thread.sleep(current.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (current.error() != null) {
            responseObserver.onError(current.error().asRuntimeException());
            return;
        }
        responseObserver.onNext(CheckPermissionResponse.newBuilder()
                .setAllowed(current.allowed())
                .setRole(current.role())
                .build());
        responseObserver.onCompleted();
    }
}

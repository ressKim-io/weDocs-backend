package io.wedocs.gateway.ws;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.CrdtEngineGrpc;
import io.wedocs.proto.crdt.ServerFrame;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/// 엔진 대역. open 시 메타데이터(doc-id·role)를 기록하고, 받은 update를 모든 스트림에 broadcast(self-echo 포함, §D-3).
/// 브리지 테스트와 인가 테스트가 공유한다 — 같은 대역을 두 벌 두면 한쪽만 계약 변경을 반영하는 드리프트가 난다.
final class FakeCrdtEngine extends CrdtEngineGrpc.CrdtEngineImplBase {

    private static final Metadata.Key<String> DOC_ID_KEY =
            Metadata.Key.of("doc-id", Metadata.ASCII_STRING_MARSHALLER);
    /// 게이트웨이가 판정한 세션 권한 — 2b가 이 값으로 write를 강제 거부한다(지금은 전달만 검증).
    private static final Metadata.Key<String> ROLE_KEY =
            Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER);

    final BlockingQueue<String> observedDocIds = new LinkedBlockingQueue<>();
    final BlockingQueue<String> observedRoles = new LinkedBlockingQueue<>();
    final BlockingQueue<ClientFrame> receivedFrames = new LinkedBlockingQueue<>();
    final BlockingQueue<String> completedStreams = new LinkedBlockingQueue<>();
    private final Set<StreamObserver<ServerFrame>> observers = ConcurrentHashMap.newKeySet();
    private final Object connectSignal = new Object();
    // 응답 observer 접근을 직렬화한다(grpc-java: 동시 호출 금지). grpc가 소유한 observer 객체를
    // 락으로 쓰지 않도록 별도 ReentrantLock 사용(락 순서 의존성 회피).
    private final ReentrantLock streamLock = new ReentrantLock();
    private Server server;

    int startOnRandomPort() {
        try {
            server = ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(this, metadataInterceptor()))
                    .build()
                    .start();
            return server.getPort();
        } catch (IOException e) {
            throw new IllegalStateException("fake engine 기동 실패", e);
        }
    }

    void stop() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    void reset() {
        observedDocIds.clear();
        observedRoles.clear();
        receivedFrames.clear();
        completedStreams.clear();
        observers.clear();
    }

    void awaitObservers(int n, long timeoutMs) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (connectSignal) {
            while (observers.size() < n) {
                long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) {
                    throw new AssertionError("engine 스트림 " + n + "개 대기 타임아웃, 현재=" + observers.size());
                }
                connectSignal.wait(remainMs);
            }
        }
    }

    @Override
    public StreamObserver<ClientFrame> sync(StreamObserver<ServerFrame> responseObserver) {
        observers.add(responseObserver);
        synchronized (connectSignal) {
            connectSignal.notifyAll();
        }
        return new StreamObserver<>() {
            @Override
            public void onNext(ClientFrame frame) {
                receivedFrames.add(frame);
                if (!frame.getUpdate().isEmpty()) {
                    broadcast(ServerFrame.newBuilder().setUpdate(frame.getUpdate()).build());
                }
            }

            @Override
            public void onError(Throwable t) {
                observers.remove(responseObserver);
            }

            @Override
            public void onCompleted() {
                observers.remove(responseObserver);
                streamLock.lock();
                try {
                    responseObserver.onCompleted();
                } finally {
                    streamLock.unlock();
                }
                completedStreams.add("completed");
            }
        };
    }

    private void broadcast(ServerFrame frame) {
        streamLock.lock();
        try {
            for (StreamObserver<ServerFrame> observer : observers) {
                observer.onNext(frame);
            }
        } finally {
            streamLock.unlock();
        }
    }

    private ServerInterceptor metadataInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <Q, P> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, P> call, Metadata headers, ServerCallHandler<Q, P> next) {
                String docId = headers.get(DOC_ID_KEY);
                if (docId != null) {
                    observedDocIds.add(docId);
                }
                String role = headers.get(ROLE_KEY);
                if (role != null) {
                    observedRoles.add(role);
                }
                return next.startCall(call, headers);
            }
        };
    }
}

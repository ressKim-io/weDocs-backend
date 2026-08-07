package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;
import io.wedocs.gateway.grpc.EngineClient;
import io.wedocs.gateway.grpc.EngineProperties;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// 엔진 대역 — 네트워크를 쓰지 않고 `openSync` 호출을 기록하고 응답 observer를 캡처한다.
/// 캡처된 응답 observer로 테스트가 **엔진 → 브라우저** 방향을 직접 구동할 수 있다.
///
/// `super(new EngineProperties("localhost:1"))`는 실제 접속을 만들지 않는다 —
/// grpc-java 채널은 지연 연결이고 `openSync`를 override해 스텁을 우회한다.
final class StubEngineClient extends EngineClient {

    /// openSync 1회분 — 열린 순서대로 쌓인다(같은 룸의 여러 세션을 구별하려면 순서가 필요하다).
    record Opened(String docId, String role, StreamObserver<ServerFrame> toClient, RequestSpy toEngine) {
    }

    final List<Opened> opened = new CopyOnWriteArrayList<>();
    boolean failOnOpen;

    StubEngineClient() {
        super(new EngineProperties("localhost:1"));
    }

    @Override
    public StreamObserver<ClientFrame> openSync(
            String docId, String role, StreamObserver<ServerFrame> responseObserver) {
        if (failOnOpen) {
            throw new IllegalStateException("engine unavailable");
        }
        RequestSpy toEngine = new RequestSpy();
        opened.add(new Opened(docId, role, responseObserver, toEngine));
        return toEngine;
    }

    /// 가장 마지막에 열린 스트림 — 세션 하나만 다루는 테스트용 편의 접근자.
    Opened latest() {
        return opened.getLast();
    }

    /// 게이트웨이 → 엔진 요청 스트림 스파이.
    static final class RequestSpy implements StreamObserver<ClientFrame> {

        final List<ClientFrame> frames = new CopyOnWriteArrayList<>();
        int completedCount;

        @Override
        public void onNext(ClientFrame value) {
            frames.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
            completedCount++;
        }
    }
}

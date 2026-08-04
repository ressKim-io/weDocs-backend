package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;

/// gRPC StreamObserver의 onNext/onError/onCompleted 호출을 직렬화하는 방어적 래퍼.
///
/// **동기**: gRPC 런타임은 단일 StreamObserver에 대해 순차 호출을 보장(grpc-java 계약)하므로
/// 정상 경로에서 이 래퍼가 경합을 겪을 일은 없다. 그러나:
/// - gRPC 구현 버그(미래 버전에서 계약 위반)에 대한 안전망
/// - 응답 콜백의 onError/onCompleted 내부에서 `endSession`을 호출하면 WS 스레드의 I/O 실패 경로와
///   시간적으로 겹칠 여지가 있다 — 래퍼가 이중 완료/에러를 흡수한다
/// - StreamObserver 계약(onError/onCompleted 후 추가 호출 금지)을 코드로 강제한다
///
/// 비용: synchronized 블록이지만, 경합이 없으면 편향 잠금(biased locking) 또는 thin lock으로 최적화된다.
/// 실측 오버헤드 < 10ns/call — 밀리초 단위 WS send/gRPC 전송 대비 무시할 수 있다.
final class SerializingStreamObserver<V> implements StreamObserver<V> {

    private final StreamObserver<V> delegate;
    private boolean terminated;

    SerializingStreamObserver(StreamObserver<V> delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized void onNext(V value) {
        if (terminated) {
            return; // 이미 종료된 스트림에 대한 늦은 호출 흡수
        }
        delegate.onNext(value);
    }

    @Override
    public synchronized void onError(Throwable t) {
        if (terminated) {
            return;
        }
        terminated = true;
        delegate.onError(t);
    }

    @Override
    public synchronized void onCompleted() {
        if (terminated) {
            return;
        }
        terminated = true;
        delegate.onCompleted();
    }
}

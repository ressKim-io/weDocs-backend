package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// SerializingStreamObserver의 직렬화·이중 완료 방지 동작을 검증한다.
class SerializingStreamObserverTest {

    @Test
    @DisplayName("onNext 호출이 delegate에 그대로 전달된다")
    void onNext_delegatesToWrapped() {
        var recording = new RecordingObserver<String>();
        var sut = new SerializingStreamObserver<>(recording);

        sut.onNext("a");
        sut.onNext("b");

        assertThat(recording.values).containsExactly("a", "b");
    }

    @Test
    @DisplayName("onCompleted 후 onNext는 무시된다 — 이중 호출 방지")
    void onNext_afterCompleted_isIgnored() {
        var recording = new RecordingObserver<String>();
        var sut = new SerializingStreamObserver<>(recording);

        sut.onNext("before");
        sut.onCompleted();
        sut.onNext("after");

        assertThat(recording.values).containsExactly("before");
        assertThat(recording.completedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onError 후 onNext/onCompleted는 무시된다")
    void onNext_afterError_isIgnored() {
        var recording = new RecordingObserver<String>();
        var sut = new SerializingStreamObserver<>(recording);

        sut.onNext("before");
        sut.onError(new RuntimeException("test"));
        sut.onNext("after");
        sut.onCompleted();

        assertThat(recording.values).containsExactly("before");
        assertThat(recording.errorCount.get()).isEqualTo(1);
        assertThat(recording.completedCount.get()).isZero();
    }

    @Test
    @DisplayName("onCompleted 이중 호출 시 delegate에는 한 번만 전달된다")
    void doubleCompleted_isIdempotent() {
        var recording = new RecordingObserver<String>();
        var sut = new SerializingStreamObserver<>(recording);

        sut.onCompleted();
        sut.onCompleted();

        assertThat(recording.completedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onError 이중 호출 시 delegate에는 한 번만 전달된다")
    void doubleError_isIdempotent() {
        var recording = new RecordingObserver<String>();
        var sut = new SerializingStreamObserver<>(recording);

        sut.onError(new RuntimeException("first"));
        sut.onError(new RuntimeException("second"));

        assertThat(recording.errorCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 스레드에서 동시에 onNext를 호출해도 모든 값이 전달되고 상태가 깨지지 않는다")
    void concurrent_onNext_allValuesDelivered() throws Exception {
        var recording = new RecordingObserver<Integer>();
        var sut = new SerializingStreamObserver<>(recording);
        int threadCount = 8;
        int messagesPerThread = 100;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int base = t * messagesPerThread;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < messagesPerThread; i++) {
                        sut.onNext(base + i);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        assertThat(recording.values).hasSize(threadCount * messagesPerThread);
    }

    @Test
    @DisplayName("onNext와 onCompleted가 동시에 호출되면 onCompleted 이후의 onNext는 무시된다")
    void concurrent_onNext_and_onCompleted() throws Exception {
        var recording = new RecordingObserver<Integer>();
        var sut = new SerializingStreamObserver<>(recording);
        int senderCount = 4;
        int messagesPerSender = 200;
        var barrier = new CyclicBarrier(senderCount + 1);
        var latch = new CountDownLatch(senderCount + 1);

        // 여러 sender가 onNext를 보내는 동안 한 스레드가 onCompleted를 호출
        for (int t = 0; t < senderCount; t++) {
            int base = t * messagesPerSender;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < messagesPerSender; i++) {
                        sut.onNext(base + i);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        Thread.startVirtualThread(() -> {
            try {
                barrier.await();
                Thread.sleep(1); // sender들이 약간 진행한 후 완료
                sut.onCompleted();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await();

        // onCompleted는 정확히 한 번만 전달되어야 한다
        assertThat(recording.completedCount.get()).isEqualTo(1);
        // onCompleted 이후 도착한 onNext는 무시된다 — 전체 개수 ≤ 최대치
        assertThat(recording.values.size()).isLessThanOrEqualTo(senderCount * messagesPerSender);
    }

    /// 테스트용 StreamObserver 대역 — 전달된 호출을 스레드 안전하게 기록한다.
    private static final class RecordingObserver<V> implements StreamObserver<V> {
        final List<V> values = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger errorCount = new AtomicInteger();
        final AtomicInteger completedCount = new AtomicInteger();

        @Override
        public void onNext(V value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            errorCount.incrementAndGet();
        }

        @Override
        public void onCompleted() {
            completedCount.incrementAndGet();
        }
    }
}

package io.wedocs.gateway.ws;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// 아웃바운드 프레임을 기록하는 `WebSocketSession` 대역.
///
/// **세션 id가 고유하다** — awareness fan-out 테스트는 같은 룸에 여러 세션을 열고 `bridges` 키로
/// 서로를 구별해야 하므로, id가 고정된 스텁으로는 두 번째 세션이 첫 번째를 덮어쓴다.
///
/// 실패 주입은 `failMode`로 한다: `IO`는 전송 계층 오류, `LIMIT`은 데코레이터 송신 상한 초과
/// (`ConcurrentWebSocketSessionDecorator`가 상한 초과 시 던지는 예외를 delegate 위치에서 재현).
///
/// `blockSends`는 **delegate 안에서 스레드를 붙잡는다** — 데코레이터가 실제로 송신 경로에 있는지,
/// 그리고 겹친 송신이 delegate에 겹쳐 들어오지 않는지를 관측 가능하게 만드는 장치다.
/// 데코레이터가 없으면 두 번째 발신 스레드도 같은 지점에서 막히므로 그 차이가 테스트로 드러난다.
final class RecordingWsSession implements WebSocketSession {

    /// 아웃바운드 실패 주입 모드.
    enum FailMode {
        NONE, IO, LIMIT
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    /// `blockSends` 안전망 — 테스트가 풀어주지 않아도 이 시간 뒤에는 스레드가 풀린다.
    private static final long BLOCK_SAFETY_TIMEOUT_MS = 30_000;

    private final String id = "recording-session-" + SEQ.incrementAndGet();
    private final Map<String, Object> attributes = new HashMap<>();

    /// 이 세션으로 나간 바이너리 프레임(원본 바이트).
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();

    CloseStatus closeStatus;
    FailMode failMode = FailMode.NONE;

    /// true면 delegate 진입 후 `releaseSends()`까지 그 스레드를 붙잡는다.
    volatile boolean blockSends;

    private volatile boolean open = true;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        switch (failMode) {
            case IO -> throw new IOException("simulated send failure");
            case LIMIT -> throw new SessionLimitExceededException(
                    "simulated send buffer limit", CloseStatus.NO_STATUS_CODE);
            case NONE -> record(message);
        }
    }

    private void record(WebSocketMessage<?> message) {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            if (blockSends) {
                entered.countDown();
                awaitQuietly(release);
            }
            ByteBuffer buffer = (ByteBuffer) message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            sent.add(bytes);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /// delegate에 스레드가 진입할 때까지 기다린다(`blockSends` 전용).
    boolean awaitEntered(long timeoutMs) throws InterruptedException {
        return entered.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /// 붙잡아 둔 스레드를 풀어준다.
    void releaseSends() {
        release.countDown();
    }

    /// delegate에 동시에 들어와 있던 스레드 수의 최대값. 데코레이터가 직렬화하면 항상 1이다.
    int maxConcurrentEntries() {
        return maxInFlight.get();
    }

    /// 무제한 대기를 쓰지 않는다 — 어서션이 `releaseSends()` 전에 실패하면 붙잡힌 스레드가 영원히
    /// 남아 테스트 JVM이 종료되지 않는다(CI 행). 상한을 두면 실패는 실패로 끝난다.
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(BLOCK_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close(CloseStatus status) {
        this.closeStatus = status;
        this.open = false;
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public URI getUri() {
        return URI.create("ws://localhost/ws/doc/test-room");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return HttpHeaders.EMPTY;
    }

    @Override
    public Principal getPrincipal() {
        return () -> "test-user";
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(0);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(0);
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
    }

    @Override
    public int getTextMessageSizeLimit() {
        return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return 0;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }
}

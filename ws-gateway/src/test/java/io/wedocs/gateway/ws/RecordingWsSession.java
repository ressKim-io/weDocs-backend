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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/// 아웃바운드 프레임을 기록하는 `WebSocketSession` 대역.
///
/// **세션 id가 고유하다** — awareness fan-out 테스트는 같은 룸에 여러 세션을 열고 `bridges` 키로
/// 서로를 구별해야 하므로, id가 고정된 스텁으로는 두 번째 세션이 첫 번째를 덮어쓴다.
///
/// 실패 주입은 `failMode`로 한다: `IO`는 전송 계층 오류, `LIMIT`은 데코레이터 송신 상한 초과
/// (`ConcurrentWebSocketSessionDecorator`가 상한 초과 시 던지는 예외를 delegate 위치에서 재현).
final class RecordingWsSession implements WebSocketSession {

    /// 아웃바운드 실패 주입 모드.
    enum FailMode {
        NONE, IO, LIMIT
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final String id = "recording-session-" + SEQ.incrementAndGet();
    private final Map<String, Object> attributes = new HashMap<>();

    /// 이 세션으로 나간 바이너리 프레임(원본 바이트).
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();

    CloseStatus closeStatus;
    FailMode failMode = FailMode.NONE;
    private boolean open = true;

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
        ByteBuffer buffer = (ByteBuffer) message.getPayload();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        sent.add(bytes);
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

package io.wedocs.gateway.ws;

import io.grpc.stub.StreamObserver;
import io.wedocs.gateway.grpc.EngineClient;
import io.wedocs.proto.crdt.ClientFrame;
import io.wedocs.proto.crdt.ServerFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// 브라우저 ↔ 엔진 브리지. WS 세션 하나당 엔진 `Sync` bidi 스트림 하나를 유지하며
/// y-websocket(바이너리) ↔ gRPC 프레임을 번역한다. (SSOT §C/§D)
///
/// **WS 단일 writer 불변식(§D-6)**: WS send는 gRPC 응답 StreamObserver(스트림당 serial 호출)에서만 한다.
/// 인바운드 핸들러는 gRPC로 forward만 하고 WS로 직접 쓰지 않으므로 동시 send가 없다 — 한 세션에 writer는
/// 응답 콜백 하나뿐이다. 위반 시 `ConcurrentWebSocketSessionDecorator`로 감싸야 한다.
@Component
public class DocWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocWebSocketHandler.class);

    private final EngineClient engineClient;
    private final YProtocolCodec codec = new YProtocolCodec();
    private final Map<String, SessionBridge> bridges = new ConcurrentHashMap<>();

    public DocWebSocketHandler(EngineClient engineClient) {
        this.engineClient = engineClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String room = roomFromUri(session.getUri());
        if (room.isEmpty()) {
            closeQuietly(session, CloseStatus.BAD_DATA.withReason("missing doc room"));
            return;
        }
        StreamObserver<ServerFrame> toClient = engineResponseObserver(session, room);
        StreamObserver<ClientFrame> toEngine = engineClient.openSync(room, toClient);
        bridges.put(session.getId(), new SessionBridge(room, toEngine));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] payload = toBytes(message.getPayload());
        // get+onNext를 computeIfPresent로 원자화한다. ConcurrentHashMap의 키 단위 락이
        // afterConnectionClosed/endSession의 remove와 상호 배제 → request StreamObserver에
        // onNext와 onCompleted가 동시 호출되지 않음(grpc-java 계약: 동시 호출 금지). (§D-6 확장)
        bridges.computeIfPresent(session.getId(), (id, bridge) -> {
            try {
                codec.decodeInbound(payload, bridge.room())
                        .ifPresent(bridge.toEngine()::onNext);
            } catch (RuntimeException e) {
                // 손상 프레임 한 개로 세션을 죽이지 않는다 — 그 프레임만 무시(엔진의 손상 update 처리와 대칭).
                log.warn("malformed frame dropped session={} room={}: {}", id, bridge.room(), e.toString());
            }
            return bridge;
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = bridges.remove(session.getId());
        if (bridge != null) {
            completeQuietly(bridge.toEngine()); // 클라가 떠났음을 엔진에 알림
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 전송 오류 뒤에는 afterConnectionClosed가 이어져 정리하므로 여기선 로깅만.
        log.warn("ws transport error session={}: {}", session.getId(), exception.toString());
    }

    /// 엔진 → 브라우저 방향. 이 콜백만이 WS의 유일한 writer다(§D-6).
    private StreamObserver<ServerFrame> engineResponseObserver(WebSocketSession session, String room) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerFrame frame) {
                codec.encodeOutbound(frame).ifPresent(bytes -> sendBinary(session, bytes));
            }

            @Override
            public void onError(Throwable t) {
                log.warn("engine stream error session={} room={}: {}", session.getId(), room, t.toString());
                endSession(session, CloseStatus.SERVER_ERROR);
            }

            @Override
            public void onCompleted() {
                endSession(session, CloseStatus.NORMAL);
            }
        };
    }

    private void sendBinary(WebSocketSession session, byte[] bytes) {
        try {
            session.sendMessage(new BinaryMessage(bytes));
        } catch (IOException e) {
            log.warn("ws send failed session={}: {}", session.getId(), e.toString());
            endSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    /// 엔진이 스트림을 끝냈거나 WS send가 실패했을 때 WS를 닫는다. 브리지를 먼저 제거해 afterConnectionClosed와 중복 정리를 막되,
    /// 제거에 성공하면 엔진 요청 스트림도 완료시킨다 — sendBinary 실패 경로에서 요청 스트림이 누수되지 않도록(이 정리 누락 시 엔진이 계속 onNext→재실패 반복).
    private void endSession(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = bridges.remove(session.getId());
        if (bridge != null) {
            completeQuietly(bridge.toEngine());
        }
        closeQuietly(session, status);
    }

    private static String roomFromUri(URI uri) {
        if (uri == null) {
            return "";
        }
        String path = uri.getPath(); // 예: /ws/doc/demo
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : "";
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("ws close failed session={}: {}", session.getId(), e.toString());
        }
    }

    private static void completeQuietly(StreamObserver<ClientFrame> toEngine) {
        try {
            toEngine.onCompleted();
        } catch (RuntimeException ignored) {
            // 이미 종료된 스트림이면 무시
        }
    }

    private record SessionBridge(String room, StreamObserver<ClientFrame> toEngine) {
    }
}

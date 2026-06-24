package io.wedocs.gateway.ws;

import io.wedocs.gateway.grpc.EngineClient;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/// 브라우저 ↔ 엔진 브리지. y-protocols(바이너리)를 디코드해 엔진 Sync 스트림으로 전달한다.
@Component
public class DocWebSocketHandler extends BinaryWebSocketHandler {

    private final EngineClient engineClient;

    public DocWebSocketHandler(EngineClient engineClient) {
        this.engineClient = engineClient;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // TODO(M1): y-protocols sync(step1/step2/update)·awareness 디코드 →
        // EngineClient.Sync 스트림으로 ClientFrame 전달, ServerFrame 수신 시 같은 docId 세션에 fan-out.
        // 현재는 골격 — 메시지 무처리.
    }
}

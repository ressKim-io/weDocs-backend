package io.wedocs.gateway.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/// y-websocket 클라이언트가 접속하는 엔드포인트 등록.
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DocWebSocketHandler handler;

    public WebSocketConfig(DocWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // TODO(M1): docId 라우팅(경로/첫 메시지) → consistent-hash 메타데이터로 엔진 분배.
        registry.addHandler(handler, "/ws/doc").setAllowedOriginPatterns("*");
    }
}

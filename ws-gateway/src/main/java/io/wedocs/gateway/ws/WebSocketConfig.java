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
        // /ws/doc/{room} 의 마지막 세그먼트가 docId(=room). 핸들러가 URI에서 추출해
        // 엔진 Sync 스트림의 gRPC 메타데이터 doc-id 로 전달한다(§D-1).
        // TODO(Phase 5): 네이티브 WS는 CSRF 보호가 없어 Origin 검사가 유일한 방어선 →
        // "*" 대신 실제 프론트엔드 Origin으로 제한. M1 로컬 데모에선 "*" 유지.
        registry.addHandler(handler, "/ws/doc/*").setAllowedOriginPatterns("*");
    }
}

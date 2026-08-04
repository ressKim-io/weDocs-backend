package io.wedocs.gateway.config;

import io.wedocs.gateway.auth.AuthHandshakeHandler;
import io.wedocs.gateway.auth.AuthHandshakeInterceptor;
import io.wedocs.gateway.auth.AuthzHandshakeInterceptor;
import io.wedocs.gateway.auth.GatewayAuthProperties;
import io.wedocs.gateway.ws.DocWebSocketHandler;
import io.wedocs.gateway.ws.GatewayProperties;
import io.wedocs.gateway.ws.RoomHandshakeInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/// y-websocket 클라이언트가 접속하는 엔드포인트 등록.
/// 순환 참조 해소를 위해 ws 패키지에서 config 패키지로 이동 — 이 클래스는 인프라 설정이지 ws 비즈니스 로직이 아니다.
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(GatewayProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    /// 엔진 tonic gRPC 디코드 한도(crdt-engine main.rs `MAX_DECODING_MESSAGE_BYTES`). 게이트웨이는 WS
    /// payload를 `ClientFrame`(+doc_id 필드·protobuf 태그/길이)으로 재포장해 엔진에 보내므로, WS에서 딱
    /// 이 크기를 받으면 재포장 후 엔진 상한을 초과해 엔진이 거부→세션이 끊긴다(config-contract-audit).
    static final int ENGINE_DECODE_LIMIT_BYTES = 4 * 1024 * 1024;
    /// `ClientFrame` 재포장 오버헤드 여유 — doc_id(≤128B)+protobuf framing은 실제 수백 바이트지만 넉넉히.
    static final int CLIENT_FRAME_OVERHEAD_BYTES = 4 * 1024;
    /// WS 바이너리 프레임 상한(secure-coding.md P2) — 엔진 상한보다 오버헤드만큼 낮춰 "게이트웨이는 수락했는데
    /// 엔진이 거부" 경계 실패를 방지. 정상 대형 sync-step2는 통과, 무한 프레임 메모리 남용은 차단.
    /// Tomcat 기본 8192B 암묵 의존 해소.
    static final int MAX_BINARY_MESSAGE_BYTES = ENGINE_DECODE_LIMIT_BYTES - CLIENT_FRAME_OVERHEAD_BYTES;
    /// 텍스트 프레임은 프로토콜상 미사용(핸들러=BinaryWebSocketHandler) — 작게 고정.
    static final int MAX_TEXT_MESSAGE_BYTES = 8 * 1024;
    /// 세션 idle 상한 — 버려진 세션 자원 회수(P2). y-websocket ping 주기보다 넉넉히, M3서 정량 재조정.
    static final long SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final DocWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;
    private final AuthzHandshakeInterceptor authzInterceptor;
    private final GatewayAuthProperties authProperties;
    private final GatewayProperties gatewayProperties;

    public WebSocketConfig(
            DocWebSocketHandler handler,
            AuthHandshakeInterceptor authInterceptor,
            AuthzHandshakeInterceptor authzInterceptor,
            GatewayAuthProperties authProperties,
            GatewayProperties gatewayProperties) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.authzInterceptor = authzInterceptor;
        this.authProperties = authProperties;
        this.gatewayProperties = gatewayProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/doc/*")
                .addInterceptors(new RoomHandshakeInterceptor(), authInterceptor, authzInterceptor)
                .setHandshakeHandler(new AuthHandshakeHandler(authProperties.subprotocol()))
                .setAllowedOrigins(gatewayProperties.allowedOrigins());
    }

    /// WS 프레임/버퍼 상한 + idle timeout 명시(secure-coding.md P2) — 런타임(Tomcat) 기본값 암묵 의존 해소.
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BYTES);
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(SESSION_IDLE_TIMEOUT_MS);
        return container;
    }
}

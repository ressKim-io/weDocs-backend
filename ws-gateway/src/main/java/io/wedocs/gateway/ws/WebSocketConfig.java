package io.wedocs.gateway.ws;

import io.wedocs.gateway.auth.AuthHandshakeHandler;
import io.wedocs.gateway.auth.AuthHandshakeInterceptor;
import io.wedocs.gateway.auth.GatewayAuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/// y-websocket 클라이언트가 접속하는 엔드포인트 등록.
@Configuration
@EnableWebSocket
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
    private final GatewayAuthProperties authProperties;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            DocWebSocketHandler handler,
            AuthHandshakeInterceptor authInterceptor,
            GatewayAuthProperties authProperties,
            @Value("${wedocs.gateway.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.authProperties = authProperties;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // /ws/doc/{room} 의 마지막 세그먼트가 docId(=room). 핸드셰이크 인터셉터가 **업그레이드 전** 검증해
        // 유효 RoomId를 세션 attribute로 넘기고, 핸들러는 그 doc-id를 엔진 Sync 메타데이터로 전달한다(§D-1).
        // 인터셉터 순서 = room(무효 room=400) → auth(무인증/무효 토큰=401). 둘 다 업그레이드 전 거절이라
        // 세션·엔진 스트림 자원이 붙기 전에 걸러진다(ADR-0021 · secure-coding.md P2). 값싼 room 검증을 먼저 둔다.
        // 인증 성공 시 AuthHandshakeHandler가 토큰이 아닌 SENTINEL 서브프로토콜만 echo해 핸드셰이크를 완성한다.
        // 네이티브 WS는 CSRF 보호가 없어 Origin 검사가 유일한 방어선(secure-coding.md P5) → "*" 금지, 화이트리스트만.
        // Origin 검사는 setAllowedOrigins가 auth 인터셉터 뒤(프레임워크 단계)에서 수행하므로, 핸드셰이크 최종
        // 성공(ws_handshake_total{ok})은 auth 인터셉터가 아니라 세션 수립 시점(DocWebSocketHandler)에서 기록한다
        // — Origin으로 최종 403될 핸드셰이크를 result=ok로 오집계하지 않기 위함(ADR-0021, code-review H-1).
        // 기본 = vite dev(5173). prod Origin은 WEDOCS_GATEWAY_ALLOWED_ORIGINS로 주입(환경 분리).
        registry.addHandler(handler, "/ws/doc/*")
                .addInterceptors(new RoomHandshakeInterceptor(), authInterceptor)
                .setHandshakeHandler(new AuthHandshakeHandler(authProperties.subprotocol()))
                .setAllowedOrigins(allowedOrigins);
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

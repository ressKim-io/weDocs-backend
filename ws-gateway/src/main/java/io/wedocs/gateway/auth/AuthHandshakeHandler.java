package io.wedocs.gateway.auth;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

/// 인증된 핸드셰이크에서 SENTINEL 서브프로토콜을 echo해 핸드셰이크를 완성한다. RFC 6455상 클라이언트가
/// 서브프로토콜을 제안하면 서버는 그중 하나를 선택·반향해야 하며, 여기선 항상 SENTINEL만 고른다 —
/// 토큰 값(제안 목록의 다른 원소)은 절대 선택하지 않아 응답 헤더로 새어 나가지 않는다(ADR-0014 · P5).
public class AuthHandshakeHandler extends DefaultHandshakeHandler {

    private final String subprotocol;

    public AuthHandshakeHandler(String subprotocol) {
        this.subprotocol = subprotocol;
    }

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        return (requestedProtocols != null && requestedProtocols.contains(subprotocol)) ? subprotocol : null;
    }
}

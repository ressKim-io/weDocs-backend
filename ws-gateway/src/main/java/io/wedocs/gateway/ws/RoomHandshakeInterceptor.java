package io.wedocs.gateway.ws;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/// /ws/doc/{room} 핸드셰이크 전 room 경계 검증(secure-coding.md P1, design-patterns.md P6). 무효 room은
/// **WS 업그레이드 전** HTTP 400으로 거절해, 무검증 room 플러드가 프로토콜 업그레이드·세션 할당 비용을
/// 치르지 못하게 한다(post-handshake close 대비 DoS 방어 강화). 유효 room은 검증된 RoomId를 세션
/// attribute로 넘겨 핸들러의 재검증을 없앤다.
public class RoomHandshakeInterceptor implements HandshakeInterceptor {

    static final String ROOM_ATTRIBUTE = "wedocs.roomId";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        Optional<RoomId> room = roomFromUri(request.getURI());
        if (room.isEmpty()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false; // 업그레이드 전 거절 — 세션이 만들어지지 않는다.
        }
        attributes.put(ROOM_ATTRIBUTE, room.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // 후처리 없음.
    }

    private static Optional<RoomId> roomFromUri(URI uri) {
        String path = uri.getPath(); // 예: /ws/doc/demo
        if (path == null) { // opaque URI 방어(표준 WS 업그레이드 URI는 항상 hierarchical)
            return Optional.empty();
        }
        int lastSlash = path.lastIndexOf('/');
        String segment = lastSlash >= 0 ? path.substring(lastSlash + 1) : "";
        // 경계 검증(P1): 무검증 세그먼트를 RoomId로 승격 — 길이·문자집합 위반은 여기서 거른다.
        return RoomId.fromPathSegment(segment);
    }
}

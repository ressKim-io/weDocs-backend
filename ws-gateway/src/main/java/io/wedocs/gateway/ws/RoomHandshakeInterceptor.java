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

    /// 검증된 RoomId(=docId)가 담기는 세션 attribute 키 — 핸들러가 엔진 메타데이터로, auth 인터셉터가 관측 doc_id로 소비한다.
    public static final String ROOM_ATTRIBUTE = "wedocs.roomId";

    /// 세션 attribute에서 RoomId를 타입 안전하게 읽는다. Spring의 `Map<String, Object>`(이종 맵)가 강제하는
    /// 캐스트를 attribute 소유자인 이 클래스 한 곳에 가둔다 — 소비자(핸들러·auth 인터셉터)는 RoomId 추상화만 다룬다.
    /// 부재·타입 불일치는 empty로 수렴(맹목적 캐스트의 ClassCastException/NPE 회피).
    public static Optional<RoomId> roomId(Map<String, Object> attributes) {
        return attributes.get(ROOM_ATTRIBUTE) instanceof RoomId roomId ? Optional.of(roomId) : Optional.empty();
    }

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

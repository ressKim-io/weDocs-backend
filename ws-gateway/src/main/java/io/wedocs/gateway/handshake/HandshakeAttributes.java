package io.wedocs.gateway.handshake;

import java.util.Map;
import java.util.Optional;

/// 핸드셰이크 인터셉터와 세션 핸들러 간 공유 attribute 키·타입 안전 접근자.
/// auth·ws 패키지 양쪽이 사용하므로 공유 `handshake` 패키지에 배치해 순환 참조를 방지한다.
public final class HandshakeAttributes {

    private HandshakeAttributes() {
    }

    /// 검증된 RoomId(=docId)가 담기는 세션 attribute 키 — 핸들러가 엔진 메타데이터로, auth 인터셉터가 관측 doc_id로 소비한다.
    public static final String ROOM_ATTRIBUTE = "wedocs.roomId";

    /// 세션 attribute에서 RoomId를 타입 안전하게 읽는다. Spring의 `Map<String, Object>`(이종 맵)가 강제하는
    /// 캐스트를 이 클래스 한 곳에 가둔다 — 소비자(핸들러·auth 인터셉터)는 RoomId 추상화만 다룬다.
    /// 부재·타입 불일치는 empty로 수렴(맹목적 캐스트의 ClassCastException/NPE 회피).
    public static Optional<RoomId> roomId(Map<String, Object> attributes) {
        return attributes.get(ROOM_ATTRIBUTE) instanceof RoomId roomId ? Optional.of(roomId) : Optional.empty();
    }
}

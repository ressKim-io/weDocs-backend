package io.wedocs.gateway.ws;

import java.util.Optional;

/// 문서 room(=docId) 도메인 식별자. 무검증 URL 세그먼트가 엔진까지 관통하는 것을 막는 경계 검증 타입
/// (secure-coding.md P1, design-patterns.md P6). 규칙은 엔진 `DocId`(crdt-engine)와 **동일**해야 한다 —
/// 길이 1..=128 · 문자집합 [A-Za-z0-9_-]. 두 경계가 어긋나면 한쪽만 통과하는 room이 생겨 방어가 뚫린다.
public record RoomId(String value) {

    static final int MAX_LENGTH = 128;

    /// 불변식: 유효하지 않은 값으로는 인스턴스가 존재할 수 없다(직접 생성 방어). 정상 경로는 fromPathSegment.
    public RoomId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("invalid room id");
        }
    }

    /// URL 경로 세그먼트를 검증해 RoomId로. 위반(빈 값·길이 초과·불허 문자)이면 empty — 호출부가 세션을 닫는다.
    static Optional<RoomId> fromPathSegment(String raw) {
        return isValid(raw) ? Optional.of(new RoomId(raw)) : Optional.empty();
    }

    private static boolean isValid(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}

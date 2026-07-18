package io.wedocs.gateway.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// RoomId 경계 검증 — 규칙은 엔진 DocId(길이 1..=128·[A-Za-z0-9_-])와 동일해야 한다.
class RoomIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"demo", "room-1", "page_42", "550e8400-e29b-41d4-a716-446655440000", "a"})
    @DisplayName("유효한 room(영숫자·하이픈·언더스코어·UUID)은 수용된다")
    void fromPathSegment_acceptsValid(String raw) {
        // Given/When/Then: 허용 문자·길이면 present
        assertThat(RoomId.fromPathSegment(raw)).isPresent();
    }

    @Test
    @DisplayName("최대 길이(128자) room도 수용된다")
    void fromPathSegment_acceptsMaxLength() {
        // Given/When/Then: 경계 길이 수용 — off-by-one 고정
        assertThat(RoomId.fromPathSegment("a".repeat(RoomId.MAX_LENGTH))).isPresent();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "room/1", "room 1", "room.1", "../etc", "명", "a\nb"})
    @DisplayName("null·빈 값·불허 문자(경로/공백/점/개행/비ASCII)는 거절된다")
    void fromPathSegment_rejectsInvalid(String raw) {
        // Given/When/Then: 위반이면 empty
        assertThat(RoomId.fromPathSegment(raw)).isEmpty();
    }

    @Test
    @DisplayName("길이 초과(129자)는 거절된다")
    void fromPathSegment_rejectsTooLong() {
        // Given/When/Then: 상한+1 거절
        assertThat(RoomId.fromPathSegment("a".repeat(RoomId.MAX_LENGTH + 1))).isEmpty();
    }

    @Test
    @DisplayName("직접 생성자는 불변식을 강제한다 — 유효하지 않으면 예외")
    void constructor_enforcesInvariant() {
        // Given/When/Then: 위반 생성 = 예외, 유효 = 값 보존
        assertThatThrownBy(() -> new RoomId("bad/id")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RoomId("ok").value()).isEqualTo("ok");
    }
}

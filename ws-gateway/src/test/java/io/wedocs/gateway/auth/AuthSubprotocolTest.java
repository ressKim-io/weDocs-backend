package io.wedocs.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Sec-WebSocket-Protocol 토큰 전달 규약 — [SENTINEL, <jwt>] 두 값에서 토큰만 안전하게 추출한다.
class AuthSubprotocolTest {

    private static final String TOKEN = "header.payload.signature";

    @Test
    @DisplayName("[SENTINEL, <jwt>]에서 토큰을 추출한다")
    void extractToken_returnsTokenWhenSentinelAndOneToken() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(List.of(AuthSubprotocol.SENTINEL, TOKEN)))
                .contains(TOKEN);
    }

    @Test
    @DisplayName("순서가 [<jwt>, SENTINEL]이어도 추출된다")
    void extractToken_orderIndependent() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(List.of(TOKEN, AuthSubprotocol.SENTINEL)))
                .contains(TOKEN);
    }

    @Test
    @DisplayName("SENTINEL이 없으면 규약 위반 — empty")
    void extractToken_emptyWhenNoSentinel() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(List.of(TOKEN))).isEmpty();
    }

    @Test
    @DisplayName("SENTINEL만 있고 토큰이 없으면 empty")
    void extractToken_emptyWhenNoToken() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(List.of(AuthSubprotocol.SENTINEL))).isEmpty();
    }

    @Test
    @DisplayName("토큰이 2개 이상이면 모호하므로 거절(empty)")
    void extractToken_emptyWhenMultipleTokens() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(List.of(AuthSubprotocol.SENTINEL, TOKEN, "other.jwt.value")))
                .isEmpty();
    }

    @Test
    @DisplayName("null·빈 목록은 empty")
    void extractToken_emptyForNullOrEmpty() {
        // Given/When/Then
        assertThat(AuthSubprotocol.extractToken(null)).isEmpty();
        assertThat(AuthSubprotocol.extractToken(List.of())).isEmpty();
    }

    @Test
    @DisplayName("한 줄에 콤마로 결합된 헤더도 개별 서브프로토콜로 평탄화된다")
    void flatten_splitsCommaJoinedHeader() {
        // Given: 한 헤더 값에 콤마로 결합("Sec-WebSocket-Protocol: wedocs.sync.v1, <jwt>")
        List<String> flattened = AuthSubprotocol.flatten(List.of(AuthSubprotocol.SENTINEL + ", " + TOKEN));

        // When/Then: 두 토큰으로 분해 → extractToken이 토큰을 얻는다
        assertThat(flattened).containsExactly(AuthSubprotocol.SENTINEL, TOKEN);
        assertThat(AuthSubprotocol.extractToken(flattened)).contains(TOKEN);
    }

    @Test
    @DisplayName("여러 줄 + 빈 값/공백은 평탄화 시 정리된다")
    void flatten_handlesMultiLineAndBlanks() {
        // Given: 여러 헤더 줄 + 공백/빈 원소 혼재
        List<String> flattened = AuthSubprotocol.flatten(Arrays.asList(AuthSubprotocol.SENTINEL, "  ", TOKEN + " ,"));

        // When/Then: 공백·빈 값 제거 후 유효 토큰만
        assertThat(flattened).containsExactly(AuthSubprotocol.SENTINEL, TOKEN);
    }

    @Test
    @DisplayName("null 헤더 목록은 빈 리스트로 평탄화된다")
    void flatten_nullYieldsEmpty() {
        // Given/When/Then
        assertThat(AuthSubprotocol.flatten(null)).isEmpty();
    }
}

package io.wedocs.gateway.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// WS 핸드셰이크 토큰 전달 규약 (ADR-0014 decision 2). 브라우저 `WebSocket` API는 커스텀 헤더를 못 실으므로
/// 토큰을 `Sec-WebSocket-Protocol` 서브프로토콜로 전달한다: 클라이언트가 `[SENTINEL, <jwt>]` 두 값을 제안하고,
/// 서버는 토큰을 검증한 뒤 SENTINEL만 echo한다(토큰은 응답에 절대 반향하지 않는다).
public final class AuthSubprotocol {

    /// 서버가 echo하는 협상 서브프로토콜 이름. 토큰이 아닌 이 값만 응답 헤더에 실린다.
    public static final String SENTINEL = "wedocs.sync.v1";

    private AuthSubprotocol() {
    }

    /// 제안된 서브프로토콜 목록에서 JWT를 추출한다. 규약 위반(SENTINEL 부재 · 토큰이 정확히 1개가 아님)이면 empty.
    /// 토큰이 2개 이상이면 어느 것을 검증할지 모호하므로 거절(fail-closed).
    public static Optional<String> extractToken(List<String> requestedProtocols) {
        if (requestedProtocols == null) {
            return Optional.empty();
        }
        boolean hasSentinel = false;
        String token = null;
        int tokenCount = 0;
        for (String raw : requestedProtocols) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.equals(SENTINEL)) {
                hasSentinel = true;
            } else {
                token = value;
                tokenCount++;
            }
        }
        return (hasSentinel && tokenCount == 1) ? Optional.of(token) : Optional.empty();
    }

    /// `Sec-WebSocket-Protocol` 헤더 값을 개별 서브프로토콜 토큰으로 평탄화한다. 헤더는 여러 줄로 오거나
    /// 한 줄에 콤마로 결합돼 올 수 있어(HTTP 헤더 규칙) 둘 다 흡수한다.
    public static List<String> flatten(List<String> headerValues) {
        if (headerValues == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : headerValues) {
            if (line == null) {
                continue;
            }
            for (String part : line.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}

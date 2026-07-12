package io.wedocs.doc.api;

import io.wedocs.doc.auth.JwtKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/// RFC 7517 JWKS 공개 엔드포인트 (ADR-0017) — 검증자(gateway Phase 2 `jwk-set-uri`,
/// Istio `RequestAuthentication.jwksUri` M5)가 공개키를 가져가는 표준 경로. 무인증 공개(공개키는 비밀 아님).
@RequiredArgsConstructor
@RestController
public class JwksController {

    private final JwtKeys jwtKeys;

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return jwtKeys.publicJwkSet().toJSONObject();
    }
}

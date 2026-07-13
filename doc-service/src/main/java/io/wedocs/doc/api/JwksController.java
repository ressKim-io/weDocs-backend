package io.wedocs.doc.api;

import io.wedocs.doc.auth.JwtKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/// RFC 7517 JWKS 공개 엔드포인트 (ADR-0017) — 검증자(gateway Phase 2 `jwk-set-uri`,
/// Istio `RequestAuthentication.jwksUri` M5)가 공개키를 가져가는 표준 경로. 무인증 공개(공개키는 비밀 아님).
@RequiredArgsConstructor
@RestController
public class JwksController {

    /// 검증자들이 주기 폴링하는 경로 — kid는 프로세스 재시작 전 불변이라 짧은 캐시가 안전.
    /// 키 로테이션(후속) 시에도 신·구 키 병존 기간이 캐시 TTL보다 길면 무중단.
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);

    private final JwtKeys jwtKeys;

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(JWKS_CACHE_TTL))
                .body(jwtKeys.publicJwkSet().toJSONObject());
    }
}

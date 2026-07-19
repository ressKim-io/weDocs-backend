package io.wedocs.gateway.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/// WS 핸드셰이크 인증 관측 메트릭 (ADR-0021 §관측 계약, MANDATORY). Micrometer 카운터를 의미 단위 메서드로
/// 캡슐화한다(Tell-Don't-Ask) — 호출부는 메트릭 이름·태그 규칙을 몰라도 결과만 알린다. 카운터 base name은
/// dot 표기(`ws.handshake`)이고 Prometheus 렌더링 시 underscore + `_total` 접미가 붙어 계약 이름
/// `ws_handshake_total`·`jwt_verify_total`·`jwks_refresh_total`로 노출된다(AuthMetricsTest가 스크레이프로 고정).
@Component
public class AuthMetrics {

    /// result 태그 값 — 인터셉터·retriever·테스트가 공유(리터럴 드리프트 방지).
    public static final String RESULT_OK = "ok";
    public static final String RESULT_AUTHN_FAIL = "authn_fail";
    public static final String RESULT_FAIL = "fail";

    static final String HANDSHAKE = "ws.handshake";     // → ws_handshake_total
    static final String JWT_VERIFY = "jwt.verify";       // → jwt_verify_total
    static final String JWKS_REFRESH = "jwks.refresh";   // → jwks_refresh_total

    private static final String TAG_RESULT = "result";

    private final MeterRegistry registry;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /// 핸드셰이크 최종 판정 1건 — result=ok|authn_fail. authz_denied·backend_error는 인가 슬라이스(2a-2)에서 추가된다.
    public void handshake(String result) {
        registry.counter(HANDSHAKE, TAG_RESULT, result).increment();
    }

    /// 토큰이 제시된 검증 시도 1건 — result=ok|fail. 무토큰은 검증 시도가 아니므로 세지 않는다(핸드셰이크 authn_fail만).
    public void jwtVerify(String result) {
        registry.counter(JWT_VERIFY, TAG_RESULT, result).increment();
    }

    /// doc-service JWKS 원격 fetch 1건(초기 로드·백그라운드 갱신 포함) — result=ok|fail.
    /// fail 지속 = 검증키 소스 단절 → fail-closed로 전 연결 거절 위험 신호(M5 알림 후보, ADR-0021).
    public void jwksRefresh(String result) {
        registry.counter(JWKS_REFRESH, TAG_RESULT, result).increment();
    }
}

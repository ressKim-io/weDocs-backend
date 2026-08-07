package io.wedocs.gateway.common.logging;

import io.wedocs.gateway.auth.AuthMetrics;
import org.slf4j.event.Level;

import java.util.Set;

/// ws-gateway 구조화 이벤트 닫힌 집합. 대시보드가 참조하는 이름·레벨·필수 속성의 SSOT다.
/// 엔트리 단위는 "이벤트 이름 × 판정" — ADR-0021이 핸드셰이크 결과별로 레벨을 다르게 규정하므로
/// (ok=INFO, authn_fail=WARN, authz_pass=DEBUG, authz_denied=WARN, backend_error=ERROR)
/// 이름 하나에 레벨 하나를 묶으면 계약을 표현할 수 없다.
///
/// `requiredAttributes` = 해당 판정에서 **항상** 산출되는 속성. 조건부 속성(측정이 수행된
/// 경우에만 존재하는 `*.duration_ms` 등)은 여기 넣지 않는다 — 넣으면 "토큰이 없어서 검증을
/// 하지 않은" authn_fail이 계약 위반으로 잡힌다.
///
/// ## `wedocs.result` 값과 `AuthMetrics.RESULT_*` 참조
/// 핸드셰이크 최종 판정 이벤트는 `wedocs.result` 값으로 `AuthMetrics.RESULT_*` 상수를 직접
/// 참조한다 — 메트릭 태그와 로그 속성이 같은 열거값을 쓰면 대시보드 조인이 성립한다.
///
/// ## `RESULT_FAIL` 제외 근거
/// `AuthMetrics.RESULT_FAIL`은 `jwt_verify` 메트릭 전용이다 — 토큰 검증 시도의 성공/실패를
/// 세는 것이지 핸드셰이크 최종 판정이 아니다. 핸드셰이크 결과에는 ok/authn_fail/authz_denied/
/// backend_error 4개만 정의되어 있고(ADR-0021 §관측 계약), `fail`이 여기 들어오면 대시보드가
/// "인증 실패"와 "검증 실패"를 구분하지 못한다.
///
/// ## `wedocs.handshake.stage` — 중간 단계 이벤트
/// `authz_pass`는 핸드셰이크의 최종 결과가 아니라 중간 체크포인트(인가 통과)이므로
/// `wedocs.result`가 아닌 `wedocs.handshake.stage`로 기록한다. 최종 결과는 이후
/// HANDSHAKE_OK로 기록된다.
public enum GatewayLogEvent {

    // ── ws_handshake × 판정 (5개) ──

    /// 핸드셰이크 완료 — 인증·인가 모두 통과, 세션 준비 완료.
    HANDSHAKE_OK(
            "ws_handshake", Level.INFO, AuthMetrics.RESULT_OK, null,
            Set.of(LogFields.DOC_ID, LogFields.USER_HASH),
            "ws handshake completed"),

    /// 핸드셰이크 거절: 인증 실패 — 무토큰 또는 검증 실패.
    HANDSHAKE_AUTHN_FAIL(
            "ws_handshake", Level.WARN, AuthMetrics.RESULT_AUTHN_FAIL, null,
            Set.of(LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws handshake rejected: authentication failed"),

    /// 핸드셰이크 중간 단계: 인가 통과 — 최종 결과(HANDSHAKE_OK)와 별개의 체크포인트.
    HANDSHAKE_AUTHZ_PASS(
            "ws_handshake", Level.DEBUG, null, "authz_pass",
            Set.of(LogFields.DOC_ID, LogFields.USER_HASH, LogFields.DOC_ROLE),
            "ws handshake authorization passed"),

    /// 핸드셰이크 거절: 인가 거부 — 권한 없음(정상 동작, 백엔드 장애와 구분).
    HANDSHAKE_AUTHZ_DENIED(
            "ws_handshake", Level.WARN, AuthMetrics.RESULT_AUTHZ_DENIED, null,
            Set.of(LogFields.DOC_ID, LogFields.USER_HASH, LogFields.ERROR_TYPE),
            "ws handshake rejected: authorization denied"),

    /// 핸드셰이크 거절: 인가 백엔드 불가 — doc-service 장애로 fail-closed 거절.
    HANDSHAKE_BACKEND_ERROR(
            "ws_handshake", Level.ERROR, AuthMetrics.RESULT_BACKEND_ERROR, null,
            Set.of(LogFields.DOC_ID, LogFields.USER_HASH, LogFields.ERROR_TYPE),
            "ws handshake rejected: authorization backend unavailable"),

    // ── 비핸드셰이크 이벤트 (10개) ──

    /// 인가 gRPC 호출 실패 — CheckPermission 예외.
    AUTHZ_CHECK_FAILED(
            "authz_check_failed", Level.WARN, null, null,
            Set.of(LogFields.RPC_SERVICE, LogFields.RPC_METHOD, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "authorization check failed"),

    /// 세션 열기 실패 — 핸드셰이크 후 세션 초기화 중 예외.
    SESSION_OPEN_FAILED(
            "ws_session_open_failed", Level.ERROR, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws session open failed"),

    /// 프레임 드롭 — 파싱·검증 실패로 메시지 버림.
    FRAME_DROPPED(
            "ws_frame_dropped", Level.WARN, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws frame dropped"),

    /// 쓰기 드롭 — viewer 읽기 전용 세션에서 쓰기 시도.
    WRITE_DROPPED(
            "ws_write_dropped", Level.DEBUG, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws write dropped: viewer read-only"),

    /// awareness 릴레이 드롭 — 상한 초과 등으로 그 프레임만 버림(세션은 유지).
    /// DEBUG인 이유: 커서 이동마다 발생할 수 있는 경로라 WARN이면 그 자체가 로그 플러딩 벡터다.
    AWARENESS_DROPPED(
            "ws_awareness_dropped", Level.DEBUG, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws awareness frame dropped"),

    /// 전송 실패 — WebSocket 전송 계층 오류.
    TRANSPORT_FAILED(
            "ws_transport_failed", Level.WARN, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.ERROR_TYPE),
            "ws transport failed"),

    /// 엔진 스트림 실패 — CRDT 엔진과의 스트림 연결 오류.
    ENGINE_STREAM_FAILED(
            "ws_engine_stream_failed", Level.WARN, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.DOC_ID, LogFields.ERROR_TYPE),
            "ws engine stream failed"),

    /// 송신 실패 — 클라이언트로의 메시지 전송 오류.
    SEND_FAILED(
            "ws_send_failed", Level.WARN, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.ERROR_TYPE),
            "ws send failed"),

    /// 송신 큐 상한 초과 — 느린 클라이언트로 세션 종료(데코레이터 버퍼·시간 상한).
    SEND_LIMIT_EXCEEDED(
            "ws_send_limit_exceeded", Level.WARN, null, null,
            Set.of(LogFields.SESSION_ID, LogFields.ERROR_TYPE),
            "ws send limit exceeded: session terminated"),

    /// 프레임 이상 — 프로토콜 계약 위반 감지(예: dual_field).
    FRAME_ANOMALY(
            "ws_frame_anomaly", Level.WARN, null, null,
            Set.of(LogFields.ERROR_TYPE),
            "ws frame anomaly detected");

    private final String eventName;
    private final Level level;
    private final String result;
    private final String stage;
    private final Set<String> requiredAttributes;
    private final String message;

    GatewayLogEvent(
            String eventName,
            Level level,
            String result,
            String stage,
            Set<String> requiredAttributes,
            String message) {
        this.eventName = eventName;
        this.level = level;
        this.result = result;
        this.stage = stage;
        this.requiredAttributes = Set.copyOf(requiredAttributes);
        this.message = message;
    }

    /// 이벤트 이름 — `event.name` 속성에 쓰이는 값. 동일 이름에 판정이 여러 개일 수 있다.
    public String eventName() {
        return eventName;
    }

    /// 로그 레벨 — taxonomy가 선언한 심각도. 콜사이트가 레벨을 고르지 않는다.
    public Level level() {
        return level;
    }

    /// `wedocs.result` 값 — 핸드셰이크 최종 판정 이벤트만 non-null.
    /// `AuthMetrics.RESULT_*` 상수를 직접 참조해 메트릭 태그와 동치.
    public String result() {
        return result;
    }

    /// `wedocs.handshake.stage` 값 — 핸드셰이크 중간 단계 이벤트만 non-null.
    public String stage() {
        return stage;
    }

    /// 해당 판정에서 항상 산출되는 속성 키 집합(불변).
    /// 조건부 속성(`*.duration_ms`)은 포함하지 않는다.
    public Set<String> requiredAttributes() {
        return requiredAttributes;
    }

    /// 고정 메시지 — 로그 메시지 본문. 플레이스홀더 없음, 값은 전부 속성으로 간다.
    public String message() {
        return message;
    }
}

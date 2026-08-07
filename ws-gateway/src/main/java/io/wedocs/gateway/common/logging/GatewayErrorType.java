package io.wedocs.gateway.common.logging;

/// ws-gateway `error.type` 속성의 닫힌 값 집합.
/// 열거값 문자열은 기존 `reason=` 태그 값을 그대로 보존한다 — 운영 대시보드·알림이 이 값에
/// 의존하므로 개명하면 대시보드가 조용히 깨진다(ADR-0021 §관측 계약).
///
/// `error.type`(OTel semconv)은 오류 분류를 문자열로 표현하는 속성이다. 임의 문자열을 허용하면
/// 대시보드가 열거값 밖의 값을 만나게 되므로, emitter가 이 enum만 받도록 타입으로 강제한다.
///
/// 도메인 엔티티 오류는 여기 정의하지 않는다 — doc-service의 `DocErrorCode.slug()`나
/// ws-gateway의 `ProtocolError`처럼 이미 카탈로그가 있는 값은 그것을 참조한다.
/// 이 enum은 카탈로그 밖의 인프라·프로토콜·인증·인가 오류 분류를 담는다.
public enum GatewayErrorType {

    // ── 인증 (authn) ──

    /// 요청에 토큰이 없음.
    NO_TOKEN("no_token"),

    /// 토큰이 존재하나 검증 실패(만료·서명 불일치 등).
    INVALID_TOKEN("invalid_token"),

    // ── 인가 (authz) ──

    /// 선행 인터셉터가 userId/docId를 설정하지 않음(인터셉터 배선 오류).
    MISSING_IDENTITY("missing_identity"),

    /// docId가 UUID 형식이 아님.
    INVALID_DOC_ID("invalid_doc_id"),

    /// 권한 없음 — CheckPermission이 DENIED를 반환.
    NO_PERMISSION("no_permission"),

    /// 허용됐으나 role을 해석할 수 없음(게이트웨이가 모르는 신규 role).
    UNKNOWN_ROLE("unknown_role"),

    /// CheckPermission gRPC 호출 자체가 실패(백엔드 불가).
    CHECK_PERMISSION_UNAVAILABLE("check_permission_unavailable"),

    // ── 세션·스트림 ──

    /// CRDT 엔진에 연결할 수 없음.
    ENGINE_UNAVAILABLE("engine_unavailable"),

    /// CRDT 엔진 스트림이 예외로 종료.
    ENGINE_STREAM_ERROR("engine_stream_error"),

    // ── 프레임·전송 ──

    /// 수신 프레임이 프로토콜 규격에 맞지 않음.
    MALFORMED_FRAME("malformed_frame"),

    /// WebSocket 전송 계층 오류.
    TRANSPORT_ERROR("transport_error"),

    /// 클라이언트로의 메시지 전송 실패.
    SEND_FAILED("send_failed"),

    /// 세션 송신 큐가 상한을 초과 — 느린 클라이언트(`ConcurrentWebSocketSessionDecorator` 상한).
    /// `send_failed`와 구분한다: 원인이 네트워크 오류가 아니라 우리가 정한 버퍼·시간 상한이다.
    SEND_BUFFER_EXCEEDED("send_buffer_exceeded"),

    // ── 비즈니스 로직 ──

    /// viewer 읽기 전용 세션에서 쓰기 시도.
    VIEWER_READ_ONLY("viewer_read_only"),

    /// ServerFrame에 state_vector와 update가 동시 설정(엔진 계약 위반 의심).
    DUAL_FIELD("dual_field");

    private final String value;

    GatewayErrorType(String value) {
        this.value = value;
    }

    /// snake_case 문자열 — `error.type` 속성의 값으로 직접 사용된다.
    public String value() {
        return value;
    }
}

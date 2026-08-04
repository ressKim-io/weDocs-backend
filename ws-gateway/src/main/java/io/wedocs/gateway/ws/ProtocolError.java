package io.wedocs.gateway.ws;

/// ws-gateway 프로토콜 코덱 계층 에러 카탈로그.
/// Lib0(varUint/varBuffer 파싱)과 YProtocolCodec(와이어 프로토콜 번역)의 에러 SSOT.
enum ProtocolError {

    // ── Lib0 파싱 에러 (IllegalArgumentException) ──

    /// varUint에 음수 입력.
    VAR_UINT_NEGATIVE("varUint는 음수를 표현하지 않는다: "),

    /// varUint 디코딩 중 63비트 초과 오버플로.
    VAR_UINT_OVERFLOW("varUint 오버플로(>63비트)"),

    /// varUint 디코딩 중 입력 바이트 부족.
    VAR_UINT_PREMATURE_END("varUint 디코드 중 입력이 조기 종료됨"),

    /// varBuffer 길이가 남은 바이트를 초과. String.format 패턴 — %d=선언 길이, %d=남은 바이트.
    VAR_BUFFER_LENGTH_EXCEEDED("varBuffer 길이가 남은 바이트를 초과: len=%d remaining=%d"),

    // ── YProtocolCodec 경고 ──

    /// ServerFrame에 state_vector와 update가 동시 설정(엔진 계약 위반 의심).
    DUAL_FIELD_WARNING(
            "ServerFrame에 state_vector와 update가 모두 설정됨 — state_vector만 전송(update 드롭). 엔진 계약 위반?");

    private final String message;

    ProtocolError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /// String.format 패턴을 포함하는 엔트리용.
    public String format(Object... args) {
        return String.format(message, args);
    }
}

package io.wedocs.doc.common.logging;

import org.slf4j.event.Level;

import java.util.Set;

/// doc-service 구조화 이벤트 닫힌 집합. 대시보드가 참조하는 이름·레벨·필수 속성의 SSOT다.
/// 엔트리 단위는 "이벤트 이름 × 판정" — 같은 이벤트 이름이라도 결과에 따라 레벨과 필수 속성이
/// 달라질 수 있으므로 이름 하나에 레벨 하나를 묶으면 계약을 표현할 수 없다.
///
/// `requiredAttributes` = 해당 판정에서 **항상** 산출되는 속성. 조건부 속성(rpc.service,
/// throwable 등 경우에 따라 존재하는 것)은 여기 넣지 않는다 — 넣으면 조건부 경로가
/// 계약 위반으로 잡힌다.
///
/// ## error.type 허용 집합
/// `DocErrorCode.slug()` 전수 ∪ `DocLogErrorType` 열거값.
/// 도메인 에러는 `DocErrorCode.slug()`를 참조하고 동일 문자열을 새로 정의하지 않는다(요구사항 10.2).
/// `DocLogErrorType`은 도메인 카탈로그 밖의 값만 담는다.
public enum DocLogEvent {

    // --- grpc_call_rejected: 요청이 도메인 처리 전에 거부됨 ---
    GRPC_CALL_REJECTED("grpc_call_rejected", Level.WARN, "rejected",
            Set.of(LogFields.ERROR_TYPE),
            "gRPC call rejected"),

    // --- grpc_call_failed: 내부 오류로 gRPC 호출이 실패함 ---
    GRPC_CALL_FAILED("grpc_call_failed", Level.ERROR, "failed",
            Set.of(LogFields.ERROR_TYPE, LogFields.RPC_METHOD),
            "gRPC call failed"),

    // --- http_request_failed: HTTP 요청 처리 중 불변식 위반 ---
    HTTP_REQUEST_FAILED("http_request_failed", Level.ERROR, "failed",
            Set.of(LogFields.ERROR_TYPE),
            "HTTP request failed"),

    // --- outbox_cleanup_completed: 아웃박스 정리 완료 ---
    OUTBOX_CLEANUP_COMPLETED("outbox_cleanup_completed", Level.INFO, "ok",
            Set.of(LogFields.OUTBOX_PUBLISHED_DELETED, LogFields.OUTBOX_UNPUBLISHED_DELETED),
            "outbox cleanup completed"),

    // --- workspace_list_capped: 워크스페이스 목록 상한 도달 ---
    WORKSPACE_LIST_CAPPED("workspace_list_capped", Level.WARN, null,
            Set.of(LogFields.USER_HASH, LogFields.WORKSPACE_LIST_CAP),
            "workspace list capped"),

    // --- jwt_ephemeral_key_generated: 설정 누락으로 임시 키 생성 ---
    JWT_EPHEMERAL_KEY_GENERATED("jwt_ephemeral_key_generated", Level.WARN, null,
            Set.of(LogFields.ERROR_TYPE),
            "JWT ephemeral key generated");

    private final String eventName;
    private final Level level;
    private final String result;
    private final Set<String> requiredAttributes;
    private final String message;

    DocLogEvent(String eventName, Level level, String result,
                Set<String> requiredAttributes, String message) {
        this.eventName = eventName;
        this.level = level;
        this.result = result;
        this.requiredAttributes = requiredAttributes;
        this.message = message;
    }

    public String eventName() {
        return eventName;
    }

    public Level level() {
        return level;
    }

    /// 판정 값 — `wedocs.result` 속성에 넣을 열거 문자열. 판정이 없는 이벤트는 null.
    public String result() {
        return result;
    }

    public Set<String> requiredAttributes() {
        return requiredAttributes;
    }

    public String message() {
        return message;
    }
}

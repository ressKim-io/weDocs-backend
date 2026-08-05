package io.wedocs.doc.common.logging;

/// 구조화 로그 Attribute_Key 규약 — 두 모듈이 동일 이름 집합을 쓰도록 키를 상수로 고정한다.
/// 키 문자열을 콜사이트에 흩뿌리면 대시보드가 오타로 조용히 깨지고, 개명 시 grep 신뢰도가 0이 된다.
///
/// ## 이름 체계
/// 소문자 + 점(`.`) 네임스페이스 구분자, 한 구성요소 안의 단어 구분만 밑줄(`duration_ms`).
/// 대응하는 OTel Semantic Conventions 속성이 있으면 그것을 쓰고(`user.hash`·`error.type`·
/// `rpc.method`·`rpc.service`·`server.port`), 없으면 `wedocs.` 네임스페이스에 정의한다.
///
/// ## 프로젝트 표준과의 관계
/// `observability.md` P1의 camelCase 예시(`docId`)는 Attribute_Key에 대해 Semantic Conventions로
/// 대체된다. 근거: OTLP_Log_Pipeline이 확정된 미래 수집 경로이므로(M4/M5) 지금 semconv를 쓰면
/// 전환 시 2차 개명이 불필요하다. 이번 마이그레이션이 이미 대시보드를 한 번 깨뜨리므로,
/// semconv 준수를 같은 라운드에 접어 두 번째 파괴를 피한다.
///
/// ## 경과 시간 단위
/// 키 접미 `_ms` + 밀리초 **정수**. OTel은 메트릭 duration에 초 단위 부동소수를 권장하지만,
/// 로그 속성에서는 단위를 키에 노출한 밀리초 정수가 (a) 기존 코드의 산출값과 일치하고
/// (b) 사람이 읽는 라인과 일치하며 (c) OTLP 정수 속성으로 무손실 승격된다. 접미 규약이
/// 단위 모호성을 제거하므로 부동소수 초 단위로 바꿔 얻을 것이 없다.
///
/// ## trace_id / span_id 예외
/// 점 표기 규칙의 명시적 예외다 — 두 이름은 OTLP에서 속성이 아니라 LogRecord의 최상위 필드
/// 이름이다. Interim_Attribute로 분류하며, OTLP_Log_Pipeline이 수집 경로가 되면 제거하고
/// 상관을 LogRecord TraceId·SpanId에 위임한다.
///
/// ## Severity_Mapping (OTLP 전방 호환)
/// | 로그 레벨 | SeverityNumber | SeverityText |
/// |---|---|---|
/// | ERROR | 17 | ERROR |
/// | WARN  | 13 | WARN  |
/// | INFO  |  9 | INFO  |
/// | DEBUG |  5 | DEBUG |
/// | TRACE |  1 | TRACE |
/// 파일 출력의 레벨 문자열은 이 SeverityText와 동일한 대문자 표기다(logstash 포맷의 `level`).
///
/// ## 속성 값 타입
/// 문자열·불리언·정수·부동소수 및 그 동종 배열만 허용한다 — OTLP가 기본 지원하는 속성 타입
/// 집합이라 내보내기 시점에 손실 변환이 남지 않는다. 이 집합 밖의 값은
/// `AttributeValues.normalize`가 단일 라인 문자열로 접는다.
///
/// ## MDC/KVP 키 소유 계층
/// MDC 키 집합 = `{trace_id, span_id}` (+ javaagent가 함께 넣는 `trace_flags`).
/// KVP 키 집합 = 이 클래스의 나머지 상수 전체.
/// 두 집합은 서로소이며, `trace_id`·`span_id`는 "MDC가 비었을 때만" KVP로 폴백하므로
/// 한 라인에 정확히 1회 출현한다.
/// 애플리케이션은 MDC에 키를 쓰지 않는다(읽기 전용) — javaagent가 채우고 요청 끝에 자동 정리한다.
///
/// ## `user.hash` 선택 근거
/// semconv `user.hash` = "익명화된 형태로 사용자를 상관시키는 해시". 마스킹 값(SHA-256 앞 5바이트
/// hex)의 의미와 정확히 일치한다. 원문 식별자용 `user.id`를 쓰면 값의 성질을 잘못 표기하게 되고,
/// PII 스캐너·보존 정책이 이 필드를 원문으로 취급한다.
///
/// ## 사용 금지 키
/// `user.id`·`user.email`·`user.full_name` — 원문 PII를 담는 속성이므로 본 프로젝트에서 사용하지 않는다.
/// 토큰·JWT·비밀번호·이메일도 로그 속성 대상에서 제외한다.
///
/// ## `event.name` 선택 근거
/// OTel이 이벤트 이름을 `event.name`으로 규정하므로 OTLP_Log_Pipeline 도입 시 재매핑이 불필요하다.
public final class LogFields {

    // --- Semantic_Convention_Attribute ---
    public static final String USER_HASH = "user.hash";
    public static final String ERROR_TYPE = "error.type";
    public static final String RPC_METHOD = "rpc.method";
    public static final String RPC_SERVICE = "rpc.service";
    public static final String SERVER_PORT = "server.port";

    // --- Wedocs_Attribute ---
    public static final String EVENT_NAME = "event.name";
    public static final String DOC_ID = "wedocs.doc.id";
    public static final String DOC_ROLE = "wedocs.doc.role";
    public static final String RESULT = "wedocs.result";
    public static final String SESSION_ID = "wedocs.session.id";
    public static final String HANDSHAKE_STAGE = "wedocs.handshake.stage";
    public static final String HANDSHAKE_VERIFY_MS = "wedocs.handshake.verify.duration_ms";
    public static final String HANDSHAKE_CHECK_PERMISSION_MS =
            "wedocs.handshake.check_permission.duration_ms";

    // --- Wedocs_Attribute (doc-service 전용) ---
    public static final String OUTBOX_PUBLISHED_DELETED = "wedocs.outbox.published_deleted";
    public static final String OUTBOX_UNPUBLISHED_DELETED = "wedocs.outbox.unpublished_deleted";
    public static final String WORKSPACE_LIST_CAP = "wedocs.workspace.list_cap";
    public static final String REQUEST_ID_LENGTH = "wedocs.request.id_length";

    // --- Interim_Attribute (OTLP 도입 시 제거) ---
    public static final String TRACE_ID = "trace_id";
    public static final String SPAN_ID = "span_id";

    /// 값 없음 — 필드를 비우는 대신 명시 placeholder를 남겨 로그 파싱이 빈 값과 누락을 구분하지
    /// 않아도 되게 한다. **문자열 속성에만 적용한다** — 정수 속성(`_ms`)은 측정이 없었으면 속성을
    /// 생략한다(타입 집합에 `-`가 들어갈 자리가 없다).
    public static final String NONE = "-";

    // --- Severity_Mapping (OTLP 전방 호환, 요구사항 11.1) ---
    public static final int SEVERITY_ERROR = 17;
    public static final int SEVERITY_WARN = 13;
    public static final int SEVERITY_INFO = 9;
    public static final int SEVERITY_DEBUG = 5;
    public static final int SEVERITY_TRACE = 1;

    private LogFields() {
    }
}

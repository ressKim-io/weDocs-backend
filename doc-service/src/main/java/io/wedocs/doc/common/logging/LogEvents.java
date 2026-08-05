package io.wedocs.doc.common.logging;

import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import java.time.Duration;

/// 구조화 이벤트 emit의 유일한 경로. 이벤트 이름·상관 필드·판정 값 주입을 한 곳에 모아
/// 콜사이트가 규약을 재현하지 않게 한다 — 콜사이트가 직접 addKeyValue를 늘어놓으면
/// 필수 속성 누락과 키 오타가 리뷰에서 드러나지 않는다.
///
/// emit 시점 규칙:
/// 1. `event.name` = taxonomy `eventName()`
/// 2. `wedocs.result` = taxonomy가 선언한 값 (null이면 생략)
/// 3. 상관 필드: MDC에 `trace_id`가 있으면 추가하지 않는다(encoder가 MDC를 이미 쓴다).
///    없거나 공백이면 `trace_id=-`를 KVP로 추가. `span_id`도 동일 규칙.
/// 4. 메시지: taxonomy 엔트리가 가진 고정 문구. 플레이스홀더 없음.
/// 5. 레벨: taxonomy 선언 레벨. 콜사이트가 레벨을 고르지 않는다.
///
/// 검사 예외를 던지지 않고 값 변환 실패를 문자열 폴백으로 흡수한다.
public final class LogEvents {

    private final DocLogEvent taxonomy;
    private LoggingEventBuilder builder;
    private Throwable throwable;

    private LogEvents(Logger logger, DocLogEvent taxonomy) {
        this.taxonomy = taxonomy;
        this.builder = createBuilder(logger, taxonomy.level());
        prefill();
    }

    /// 이벤트 1건 시작 — event.name·wedocs.result·상관 필드를 미리 채운 빌더를 돌려준다.
    public static LogEvents event(Logger logger, DocLogEvent taxonomy) {
        return new LogEvents(logger, taxonomy);
    }

    /// 문자열 속성 첨부 — null이면 속성을 생략한다.
    public LogEvents attr(String key, String value) {
        if (value != null) {
            builder = builder.addKeyValue(key, value);
        }
        return this;
    }

    /// 정수 속성 첨부.
    public LogEvents attr(String key, long value) {
        builder = builder.addKeyValue(key, value);
        return this;
    }

    /// 범용 속성 첨부 — 값은 `AttributeValues.normalize`를 거친다(허용 타입 밖이면 단일 라인 문자열).
    /// null 값은 속성을 생략한다.
    public LogEvents attr(String key, Object value) {
        Object normalized = AttributeValues.normalize(value);
        if (normalized != null) {
            builder = builder.addKeyValue(key, normalized);
        }
        return this;
    }

    /// 경과 시간 — Duration을 밀리초 정수로 접어 `_ms` 키에 넣는다(요구사항 3.5/4.4).
    /// null이면 속성을 생략한다(측정이 수행되지 않은 경우).
    public LogEvents durationMs(String key, Duration elapsed) {
        if (elapsed != null) {
            builder = builder.addKeyValue(key, elapsed.toMillis());
        }
        return this;
    }

    /// 오류 분류 — enum 전용(요구사항 4.3).
    public LogEvents errorType(DocLogErrorType type) {
        if (type != null) {
            builder = builder.addKeyValue(LogFields.ERROR_TYPE, type.value());
        }
        return this;
    }

    /// 오류 분류 — 문자열 값 직접 지정. `DocErrorCode.slug()`처럼 외부 카탈로그를 참조할 때 사용.
    /// 도메인 에러는 `DocErrorCode.slug()`를 참조하고 동일 문자열을 새로 정의하지 않는다(요구사항 10.2).
    public LogEvents errorType(String type) {
        if (type != null) {
            builder = builder.addKeyValue(LogFields.ERROR_TYPE, type);
        }
        return this;
    }

    /// 예외 동반 — throwable 인자로 전달해 스택트레이스가 전용 필드로 직렬화되게 한다(4.7).
    public LogEvents cause(Throwable t) {
        this.throwable = t;
        return this;
    }

    /// 선언된 레벨로 emit한다. 메시지는 taxonomy가 소유한 고정 문구.
    public void log() {
        if (throwable != null) {
            builder = builder.setCause(throwable);
        }
        builder.log(taxonomy.message());
    }

    private void prefill() {
        // event.name
        builder = builder.addKeyValue(LogFields.EVENT_NAME, taxonomy.eventName());

        // wedocs.result (null이면 생략)
        if (taxonomy.result() != null) {
            builder = builder.addKeyValue(LogFields.RESULT, taxonomy.result());
        }

        // 상관 필드 — MDC에 있으면 추가하지 않고(encoder가 MDC를 이미 쓴다),
        // 없거나 공백이면 KVP로 폴백해 한 라인에 정확히 1회 출현을 보장한다.
        if (!LogCorrelation.mdcHasTraceId()) {
            builder = builder.addKeyValue(LogFields.TRACE_ID, LogCorrelation.traceId());
        }
        // span_id — MDC에 값이 있으면 encoder가 쓰므로 추가하지 않는다.
        // 없을 때도 폴백을 넣지 않는다(trace_id와 달리 placeholder 없음).
    }

    private static LoggingEventBuilder createBuilder(Logger logger, Level level) {
        return switch (level) {
            case ERROR -> logger.atError();
            case WARN -> logger.atWarn();
            case INFO -> logger.atInfo();
            case DEBUG -> logger.atDebug();
            case TRACE -> logger.atTrace();
        };
    }
}

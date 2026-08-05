package io.wedocs.doc.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;

/// 테스트 전용 로그 캡처 — `ListAppender<ILoggingEvent>`를 대상 로거에 붙여
/// KVP를 타입 안전 접근자로, 레벨·throwable을 그대로 노출한다.
///
/// **문자열 어서션 API를 제공하지 않는다** — 렌더링 문자열 의존 테스트가 새로 들어오는 것을
/// API 표면에서 막는다(요구사항 9.1). 로그 검증은 KVP·MDC·레벨 단위로만 수행한다.
///
/// 사용법:
/// ```java
/// try (var logs = CapturedLogs.of(MyClass.class)) {
///     // ... 로그를 남기는 코드 ...
///     var event = logs.events().getFirst();
///     assertThat(event.getString("event.name")).isEqualTo("grpc_call_rejected");
///     assertThat(event.level()).isEqualTo(Level.WARN);
/// }
/// ```
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private CapturedLogs(Logger logger) {
        this.logger = logger;
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    /// 지정 클래스의 로거에 캡처를 시작한다.
    public static CapturedLogs of(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        return new CapturedLogs(logger);
    }

    /// 지정 이름의 로거에 캡처를 시작한다.
    public static CapturedLogs of(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        return new CapturedLogs(logger);
    }

    /// 캡처된 이벤트 목록을 반환한다. 각 이벤트는 타입 안전 KVP 접근자·레벨·throwable을 노출한다.
    public List<CapturedEvent> events() {
        return appender.list.stream()
                .map(CapturedEvent::from)
                .toList();
    }

    /// 캡처된 원시 `ILoggingEvent` 목록을 반환한다.
    public List<ILoggingEvent> rawEvents() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /// 캡처된 로그 이벤트 1건 — KVP를 타입 안전 접근자로, 레벨·throwable을 그대로 노출한다.
    /// 렌더링 문자열 접근 API는 의도적으로 제공하지 않는다.
    public record CapturedEvent(
            ch.qos.logback.classic.Level level,
            List<KeyValuePair> kvp,
            Map<String, String> mdc,
            Throwable throwable
    ) {

        /// 문자열 KVP 값 조회 — 키가 없거나 타입이 다르면 null.
        public String getString(String key) {
            return findValue(key, String.class);
        }

        /// 정수(Long) KVP 값 조회 — 키가 없거나 타입이 다르면 null.
        public Long getLong(String key) {
            return findValue(key, Long.class);
        }

        /// 불리언 KVP 값 조회 — 키가 없거나 타입이 다르면 null.
        public Boolean getBoolean(String key) {
            return findValue(key, Boolean.class);
        }

        /// KVP 값을 Object로 조회 — 키가 없으면 null.
        public Object getValue(String key) {
            for (KeyValuePair pair : kvp) {
                if (pair.key.equals(key)) {
                    return pair.value;
                }
            }
            return null;
        }

        /// 해당 키가 KVP에 존재하는지 확인한다.
        public boolean hasKey(String key) {
            for (KeyValuePair pair : kvp) {
                if (pair.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }

        private <T> T findValue(String key, Class<T> type) {
            for (KeyValuePair pair : kvp) {
                if (pair.key.equals(key) && type.isInstance(pair.value)) {
                    return type.cast(pair.value);
                }
            }
            return null;
        }

        static CapturedEvent from(ILoggingEvent event) {
            List<KeyValuePair> pairs = event.getKeyValuePairs() != null
                    ? List.copyOf(event.getKeyValuePairs())
                    : List.of();

            Map<String, String> mdcMap = event.getMDCPropertyMap() != null
                    ? Map.copyOf(event.getMDCPropertyMap())
                    : Map.of();

            Throwable cause = null;
            if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
                cause = proxy.getThrowable();
            }

            return new CapturedEvent(event.getLevel(), pairs, mdcMap, cause);
        }
    }
}

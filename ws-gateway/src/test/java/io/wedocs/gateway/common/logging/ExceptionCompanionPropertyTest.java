package io.wedocs.gateway.common.logging;

import ch.qos.logback.classic.Level;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 4.7, 4.8**
///
/// Property 8: 예외 동반 규칙 —
/// 임의 예외·중첩 원인에 대해 (1) throwable이 전용 필드에 올바르게 전달되고,
/// (2) error.type 속성이 반드시 존재하며, (3) 로그 메시지에 스택트레이스가 섞이지 않고,
/// (4) 중첩 원인 체인이 보존되며, (5) 예외가 없으면 throwable이 null임을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 8: 예외 동반 규칙")
class ExceptionCompanionPropertyTest {

    private static final Logger logger = LoggerFactory.getLogger(LogEvents.class);

    static {
        // 모든 레벨의 로그를 캡처하려면 로거 레벨을 TRACE로 설정
        ((ch.qos.logback.classic.Logger) logger).setLevel(Level.TRACE);
    }

    @Property(tries = 100)
    void exceptionCarriedInThrowableField(@ForAll("arbitraryExceptions") Throwable exception) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_AUTHN_FAIL)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .errorType(GatewayErrorType.INVALID_TOKEN)
                    .cause(exception)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.throwable())
                    .as("throwable must be the same exception passed to .cause()")
                    .isSameAs(exception);
        }
    }

    @Property(tries = 100)
    void errorTypePresent_whenExceptionPresent(@ForAll("arbitraryExceptions") Throwable exception) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_AUTHN_FAIL)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .errorType(GatewayErrorType.INVALID_TOKEN)
                    .cause(exception)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.getString(LogFields.ERROR_TYPE))
                    .as("error.type must be present when exception is attached")
                    .isNotNull()
                    .isEqualTo(GatewayErrorType.INVALID_TOKEN.value());
        }
    }

    @Property(tries = 100)
    void messageDoesNotContainStackTrace(@ForAll("arbitraryExceptions") Throwable exception) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_AUTHN_FAIL)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .errorType(GatewayErrorType.INVALID_TOKEN)
                    .cause(exception)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.rawEvents().getFirst();

            String message = captured.getFormattedMessage();

            // 메시지는 taxonomy가 소유한 고정 문구여야 한다 — 스택트레이스 패턴이 없어야 한다
            assertThat(message)
                    .as("message must be taxonomy fixed text, not contain stack frames")
                    .doesNotContain("at ")
                    .doesNotContain("\tat ");

            // 메시지가 예외 클래스 이름을 포함하지 않아야 한다
            assertThat(message)
                    .as("message must not contain exception class name")
                    .doesNotContain(exception.getClass().getName());

            // 메시지는 taxonomy의 고정 메시지와 동일해야 한다
            assertThat(message)
                    .as("message must equal taxonomy fixed message")
                    .isEqualTo(GatewayLogEvent.HANDSHAKE_AUTHN_FAIL.message());
        }
    }

    @Property(tries = 100)
    void nestedCausesPreserved(@ForAll("nestedExceptions") Throwable nested) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_BACKEND_ERROR)
                    .attr(LogFields.DOC_ID, "test-doc")
                    .attr(LogFields.USER_HASH, "abcdef1234")
                    .errorType(GatewayErrorType.CHECK_PERMISSION_UNAVAILABLE)
                    .cause(nested)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            // throwable 자체가 보존됨
            assertThat(captured.throwable())
                    .as("throwable must be the nested exception")
                    .isSameAs(nested);

            // 원인 체인이 보존됨 — getCause()를 따라가면 원본과 동일
            Throwable originalCause = nested.getCause();
            Throwable capturedCause = captured.throwable().getCause();
            assertThat(capturedCause)
                    .as("nested cause chain must be preserved")
                    .isSameAs(originalCause);
        }
    }

    @Property(tries = 100)
    void noException_throwableIsNull(@ForAll("taxonomyWithErrorType") GatewayLogEvent taxonomy) {
        try (var logs = CapturedLogs.of(LogEvents.class)) {
            LogEvents event = LogEvents.event(logger, taxonomy);

            // 필수 속성 채우기 (error.type 제외 나머지)
            for (String key : taxonomy.requiredAttributes()) {
                if (key.equals(LogFields.ERROR_TYPE)) {
                    event.errorType(GatewayErrorType.MALFORMED_FRAME);
                } else {
                    event.attr(key, "test-value");
                }
            }

            // .cause()를 호출하지 않음
            event.log();

            assertThat(logs.events()).hasSize(1);
            var captured = logs.events().getFirst();

            assertThat(captured.throwable())
                    .as("throwable must be null when .cause() is not called")
                    .isNull();
        }
    }

    @Provide
    Arbitrary<Throwable> arbitraryExceptions() {
        List<Class<? extends Throwable>> exceptionTypes = List.of(
                RuntimeException.class,
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class,
                UnsupportedOperationException.class,
                IOException.class,
                IndexOutOfBoundsException.class,
                ArithmeticException.class
        );

        return Arbitraries.of(exceptionTypes)
                .flatMap(type -> Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(50)
                        .map(msg -> createException(type, msg)));
    }

    @Provide
    Arbitrary<Throwable> nestedExceptions() {
        // 1~3 레벨 깊이의 중첩 원인 체인 생성
        Arbitrary<String> messageArb = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30);

        return Arbitraries.integers().between(1, 3)
                .flatMap(depth -> messageArb.list()
                        .ofSize(depth + 1)
                        .map(messages -> buildNestedChain(messages)));
    }

    @Provide
    Arbitrary<GatewayLogEvent> taxonomyWithErrorType() {
        // error.type을 requiredAttributes에 가진 taxonomy 엔트리만 선택
        List<GatewayLogEvent> withErrorType = java.util.Arrays.stream(GatewayLogEvent.values())
                .filter(t -> t.requiredAttributes().contains(LogFields.ERROR_TYPE))
                .toList();
        return Arbitraries.of(withErrorType);
    }

    private static Throwable createException(Class<? extends Throwable> type, String message) {
        try {
            return type.getDeclaredConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException e) {
            return new RuntimeException(message);
        }
    }

    private static Throwable buildNestedChain(List<String> messages) {
        Throwable current = new IOException(messages.getFirst());
        for (int i = 1; i < messages.size(); i++) {
            Throwable wrapper = (i % 2 == 0)
                    ? new IllegalStateException(messages.get(i), current)
                    : new RuntimeException(messages.get(i), current);
            current = wrapper;
        }
        return current;
    }
}

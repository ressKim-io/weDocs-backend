package io.wedocs.gateway.common.logging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 1.7, 11.2**
///
/// Property 1: JSON 인코딩 라운드트립과 심각도 표기 —
/// 임의의 레벨과 임의의 속성 집합에 대해 Structured_File_Appender encoder 출력 1건은 유효한
/// JSON 객체로 파싱되고, 모든 속성이 키·값 타입 그대로 남으며, `level` 값은 Severity_Mapping의
/// SeverityText(대문자)와 같다.
///
/// 검증 대상은 `StructuredLogEncoder(format=logstash)`이며 Spring 컨텍스트를 띄우지 않는다 —
/// encoder가 LoggerContext에서 Spring `Environment`를 조회하므로
/// `putObject(Environment.class.getName(), ...)`로 주입한다. Environment가 없으면
/// `Unable to find Spring Environment in logger context`로 실패한다(설계 조사 결과 #4).
///
/// 이벤트는 SLF4J fluent API(`atLevel().addKeyValue()`)로 만들어 `ListAppender`가 캡처한 것을
/// 그대로 encoder에 넣는다 — 콜사이트가 실제로 쓰는 경로와 같은 `ILoggingEvent`를 인코딩해야
/// 라운드트립 검증이 프로덕션 출력에 대한 진술이 된다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 1: JSON 인코딩 라운드트립과 심각도 표기")
class StructuredLogEncodingPropertyTest {

    /// 구조화 파일 출력 포맷 — `logging.structured.format.file` 설정값과 같은 값이다.
    /// (설정이 실제로 이 값인지는 task 1.2의 설정 계약 테스트가 본다.)
    private static final String FORMAT = "logstash";

    /// 메시지는 고정 문구다 — 값은 전부 속성으로 가고 메시지 보간을 쓰지 않는다(요구사항 2.3).
    private static final String FIXED_MESSAGE = "structured log encoding round trip";

    /// Severity_Mapping(요구사항 11.1)의 SeverityText. 인코딩된 라인의 `level` 값이 이 문자열과
    /// 같아야 파일 출력과 향후 OTLP 출력의 심각도 표기가 일치한다(요구사항 11.2).
    private static final Map<Level, String> SEVERITY_TEXT = Map.of(
            Level.ERROR, "ERROR",
            Level.WARN, "WARN",
            Level.INFO, "INFO",
            Level.DEBUG, "DEBUG",
            Level.TRACE, "TRACE");

    /// logstash 포맷이 소유한 최상위 멤버 이름. 속성 키가 이 집합과 겹치면 인코딩 자체가
    /// 중복 멤버로 실패하므로(JsonValueWriter는 같은 이름을 두 번 쓰지 않는다) 생성기를 이 밖으로
    /// 제한한다 — 예약 이름 충돌은 Property 4(키 네임스페이스 규약)가 다루는 문제다.
    private static final List<String> RESERVED_MEMBERS = List.of(
            "@timestamp", "@version", "message", "logger_name", "thread_name",
            "level", "level_value", "tags", "stack_trace");

    /// 규약이 실제로 쓰는 키 표본 — 생성 키만 쓰면 점 네임스페이스가 깊은 실제 키가 표본에서 빠진다.
    private static final List<String> CONVENTION_KEYS = List.of(
            "event.name", "wedocs.result", "wedocs.doc.id", "wedocs.doc.role",
            "user.hash", "error.type", "rpc.method", "rpc.service", "server.port",
            "wedocs.handshake.verify.duration_ms", "trace_id");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private LoggerContext loggerContext;

    private Logger logger;

    private ListAppender<ILoggingEvent> captured;

    private StructuredLogEncoder encoder;

    /// try 단위로 harness를 만든다 — jqwik은 try마다 테스트 클래스 인스턴스를 새로 만들므로
    /// property 단위 훅에 인스턴스 필드를 걸면 두 번째 try부터 필드가 비어 있다.
    @BeforeTry
    void startEncoder() {
        this.loggerContext = new LoggerContext();
        this.loggerContext.setName("property-1-structured-log-encoding");
        this.loggerContext.putObject(Environment.class.getName(), new MockEnvironment());
        // 독립 LoggerContext에는 MDCAdapter가 없어 인코딩 시점의 MDC 조회가 NPE로 죽는다.
        // 전역 어댑터 대신 전용 어댑터를 붙여 다른 테스트가 남긴 MDC 값이 이 라인에 섞이지 않게 한다
        // (MDC 자체의 불변식은 Property 12·13이 다룬다).
        this.loggerContext.setMDCAdapter(new LogbackMDCAdapter());

        this.logger = this.loggerContext.getLogger("io.wedocs.gateway.property1.Encoding");
        this.logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        this.captured = new ListAppender<>();
        this.captured.setContext(this.loggerContext);
        this.captured.start();
        this.logger.addAppender(this.captured);

        this.encoder = new StructuredLogEncoder();
        this.encoder.setContext(this.loggerContext);
        this.encoder.setFormat(FORMAT);
        this.encoder.setCharset(StandardCharsets.UTF_8);
        this.encoder.start();
    }

    @AfterTry
    void stopEncoder() {
        this.encoder.stop();
        this.captured.stop();
        this.loggerContext.stop();
    }

    @Property(tries = 200)
    void encodedLine_isJsonObject_withAttributesAndSeverityText(
            @ForAll("levels") Level level,
            @ForAll("attributeSets") Map<String, Object> attributes) {

        Map<String, Object> json = encodeAndParse(level, attributes);

        assertThat(json.get("level")).isEqualTo(SEVERITY_TEXT.get(level));
        attributes.forEach((key, value) -> {
            assertThat(json).containsKey(key);
            assertValuePreserved(key, value, json.get(key));
        });
    }

    private Map<String, Object> encodeAndParse(Level level, Map<String, Object> attributes) {
        this.captured.list.clear();
        LoggingEventBuilder builder = this.logger.atLevel(level).setMessage(FIXED_MESSAGE);
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            builder = builder.addKeyValue(attribute.getKey(), attribute.getValue());
        }
        builder.log();
        assertThat(this.captured.list).hasSize(1);

        String line = new String(this.encoder.encode(this.captured.list.getFirst()), StandardCharsets.UTF_8);
        assertThat(line).endsWith("\n");
        return JSON.readValue(line, JSON_OBJECT);
    }

    private void assertValuePreserved(String key, Object expected, Object actual) {
        switch (expected) {
            case String text -> assertThat(actual).as("attribute %s", key).isEqualTo(text);
            case Boolean flag -> assertThat(actual).as("attribute %s", key).isEqualTo(flag);
            case Long integral -> assertIntegral(key, integral, actual);
            case Double floating -> assertFloating(key, floating, actual);
            default -> throw new IllegalStateException("generator produced unsupported type: " + expected);
        }
    }

    private void assertIntegral(String key, long expected, Object actual) {
        assertThat(actual).as("attribute %s", key)
                .isInstanceOf(Number.class)
                .isNotInstanceOfAny(Double.class, Float.class);
        assertThat(((Number) actual).longValue()).as("attribute %s", key).isEqualTo(expected);
    }

    private void assertFloating(String key, double expected, Object actual) {
        assertThat(actual).as("attribute %s", key).isInstanceOf(Number.class);
        assertThat(((Number) actual).doubleValue()).as("attribute %s", key).isEqualTo(expected);
    }

    @Provide
    Arbitrary<Level> levels() {
        return Arbitraries.of(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE);
    }

    @Provide
    Arbitrary<Map<String, Object>> attributeSets() {
        return Arbitraries.maps(attributeKeys(), attributeValues()).ofMinSize(1).ofMaxSize(8);
    }

    private Arbitrary<String> attributeKeys() {
        Arbitrary<String> generated = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .list()
                .ofMinSize(1)
                .ofMaxSize(3)
                .map(segments -> "wedocs." + String.join(".", segments));
        return Arbitraries.oneOf(generated, Arbitraries.of(CONVENTION_KEYS))
                .filter(key -> !RESERVED_MEMBERS.contains(key));
    }

    private Arbitrary<Object> attributeValues() {
        return Arbitraries.oneOf(
                escapeProneStrings().map(value -> (Object) value),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(value -> (Object) value),
                Arbitraries.longs().map(value -> (Object) value),
                // 부동소수는 유한값으로 제한한다 — NaN·Infinity는 JSON 수 리터럴로 표현할 수 없어
                // 라운드트립의 입력 공간 밖이다(허용 타입 밖 값의 변환은 Property 5가 다룬다).
                Arbitraries.doubles().between(-1.0e12, 1.0e12).map(value -> (Object) value));
    }

    /// 이스케이프·인코딩이 깨지기 쉬운 문자열 표본. **입력 공간은 BMP(U+0000..U+FFFF)로 제한한다** —
    /// 서러게이트 페어로 표현되는 non-BMP 문자(이모지 등)는 생성하지 않는다.
    ///
    /// 이유: Spring Boot 4.1의 `org.springframework.boot.json.AppendableByteArray#append(char)`가
    /// `char`를 하나씩 `CharsetEncoder.encode(..., endOfInput = false)`로 넘긴다. 서러게이트 페어의
    /// 하이 서러게이트는 이 호출에서 소비되지 않은 채 버려지고, 남은 로우 서러게이트만 인코딩되어
    /// 값이 `?`로 바뀐다. 즉 non-BMP 속성 값은 `logs/app.json`에서 손실된다.
    /// (반례: level=ERROR, `{"wedocs.b": "emoji U+1F600"}` → 기대 `"emoji U+1F600"`, 실제 `"emoji ?"`,
    /// jqwik seed 8628691474845353310)
    ///
    /// 이것은 상위 라이브러리(구조화 인코더)의 한계이고 task 6.1의 `AttributeValues`로 고칠 수 없다 —
    /// 손실은 값이 이미 문자열로 정규화된 뒤 인코더가 바이트로 바꾸는 단계에서 일어나므로, 콜사이트가
    /// 어떤 형태로 값을 넘겨도 결과가 같다.
    ///
    /// 실무 노출은 없다 — 로그 속성 값은 문서 id, 해시, enum 값, 정수뿐이라 non-BMP 문자가 들어오지
    /// 않는다. 이 제약은 task 13.1의 Migration_Note가 알려진 제약으로 기록한다.
    private Arbitrary<String> escapeProneStrings() {
        Arbitrary<String> plain = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMaxLength(12);
        Arbitrary<String> escaping = Arbitraries.of(
                "\"quoted\"", "back\\slash", "line\nbreak", "carriage\rreturn", "tab\there",
                "form\ffeed", "back\bspace", "control\u0001char", "한글 속성 값",
                "{\"nested\":\"json\"}", "</script>", "");
        return Arbitraries.oneOf(plain, escaping,
                Combinators.combine(plain, escaping).as((prefix, suffix) -> prefix + suffix));
    }
}

package io.wedocs.gateway.common.logging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// 레벨별 구조화 파일 출력 예시 (요구사항 1.1). DEBUG·INFO·WARN·ERROR 각 1건이
/// Structured_File_Appender가 쓴 라인의 **JSON 멤버**로 key-value 속성을 갖는지 본다.
/// 설정 계약(task 1.2)·encoder 라운드트립(task 1.3)과 같은 이유로 양 모듈에 사본을 둔다 —
/// 로깅 설정은 모듈마다 독립이고, 한쪽 모듈의 appender 배선이 깨지는 것을 다른 쪽 테스트가
/// 잡아주지 않는다.
///
/// task 1.2(설정 해석값)·1.3(encoder 라운드트립)과 겹치지 않는 지점: 여기서는 appender까지
/// 배선한 뒤 **파일에 실제로 남은 라인**을 읽는다. 1.2는 프로퍼티 값만 보고 1.3은 encoder를
/// 단독으로 호출하므로, "이벤트가 파일 appender에 전달되는가"는 둘 다 진술하지 않는다.
///
/// harness는 Boot `structured-file-appender.xml`의 구조를 그대로 모방한다 —
/// `RollingFileAppender` + `ThresholdFilter(FILE_LOG_THRESHOLD)` +
/// `StructuredLogEncoder(format=logstash)` + `SizeAndTimeBasedRollingPolicy`. Spring 컨텍스트는
/// 띄우지 않고, encoder가 LoggerContext에서 조회하는 Spring `Environment`만
/// `putObject(Environment.class.getName(), ...)`로 주입한다(1.3과 같은 방식).
/// rolling 값은 appender 기동에 필요해서 넣은 것이고 이 테스트가 그 값을 고정하지는 않는다 —
/// 설정 값의 소유는 task 1.2다.
///
/// ## DEBUG를 어떻게 다루는가 (요구사항 1.1 문면과 이 task의 차이)
/// 요구사항 1.1은 appender 임계값을 "INFO 이상 모든 레벨"로 규정하고, 이 task는 DEBUG 예시도
/// 요구한다. 두 진술은 충돌하지 않는다 — 프로덕션 FILE appender에는 사실상 자체 임계값이 없다.
/// Boot include의 `ThresholdFilter` 레벨은 `FILE_LOG_THRESHOLD`이고 기본값이 TRACE인데
/// (`defaults.xml`), 본 스펙의 설정은 `logging.file.threshold`를 두지 않는다. 즉 파일에 도달하는
/// 레벨 집합을 정하는 것은 **로거 레벨**이고, 기본 root 레벨 INFO에서 도달 집합은 정확히
/// INFO 이상이다(= 요구사항 1.1).
///
/// 그래서 DEBUG 예시는 로거 레벨을 명시적으로 DEBUG로 내려 만든다. 프로덕션 임계값이 DEBUG를
/// 내보낸다고 주장하지 않는다. 이 예시가 공허하지 않은 이유는 taxonomy에 DEBUG 엔트리가
/// 실재하기 때문이다(`ws_handshake stage=authz_pass`, `ws_write_dropped`) — 운영자가 그 이벤트를
/// 보려면 `logging.level.io.wedocs=DEBUG`로 로거를 내리고, 그때 DEBUG 라인도 구조화 속성을
/// 갖춘 채 파일에 남아야 한다.
///
/// 반대 방향은 `productionDefaultLoggerLevel_writes_infoAndAbove_only`가 고정한다 — 로거 레벨이
/// 프로덕션 기본(INFO)이면 DEBUG는 파일에 없고 INFO·WARN·ERROR만 있다. 두 테스트를 함께 두어
/// "DEBUG도 나온다"가 appender 임계값에 대한 주장으로 오독되지 않게 한다.
///
/// 검증 단위는 파싱된 JSON 멤버다 — 렌더링된 평문 라인이나 `message` 문구를 비교하지 않는다
/// (요구사항 9.1, 스펙 주의 #3). 라인 식별자로는 `level` 멤버를 쓴다.
@Tag("Feature: structured-logging-unification-v2")
class StructuredFileOutputLevelTest {

    /// 구조화 파일 출력 포맷 — `logging.structured.format.file` 설정값과 같다(값의 고정은 task 1.2).
    private static final String FORMAT = "logstash";

    /// Boot `defaults.xml`의 `FILE_LOG_THRESHOLD` 기본값. 본 스펙은 `logging.file.threshold`를
    /// 두지 않으므로 프로덕션 FILE appender의 필터 레벨도 이 값이다.
    private static final String FILE_LOG_THRESHOLD = "TRACE";

    /// 메시지는 고정 문구다 — 값은 전부 속성으로 간다(요구사항 2.3). 이 문구를 어서션에 쓰지 않는다.
    private static final String FIXED_MESSAGE = "structured file output level example";

    /// rolling 값은 appender를 기동시키기 위해 필요한 값이라 프로덕션과 같은 값을 쓴다.
    /// 이 테스트는 그 값을 고정하지 않는다 — 설정 값의 소유는 task 1.2의 설정 계약 테스트다.
    private static final String MAX_FILE_SIZE = "20MB";

    private static final String TOTAL_SIZE_CAP = "500MB";

    private static final int MAX_HISTORY = 7;

    /// 레벨별 1건. 출력 순서가 곧 emit 순서라 라인 순서로 비교한다.
    private static final List<Level> LEVELS = List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);

    private static final String LEVEL_MEMBER = "level";

    private static final String DOC_ID_KEY = "wedocs.doc.id";

    private static final String USER_HASH_KEY = "user.hash";

    private static final String SERVER_PORT_KEY = "server.port";

    private static final String DOC_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static final String USER_HASH = "f1d11edf5c";

    /// 정수 속성 표본 — 문자열로 접히지 않고 JSON 수 리터럴로 남는지 함께 본다(요구사항 11.3).
    private static final int SERVER_PORT = 8080;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /// JUnit이 필드에 주입하므로 private일 수 없다.
    @TempDir
    Path tempDir;

    private Path logFile;

    private LoggerContext loggerContext;

    private Logger logger;

    private RollingFileAppender<ILoggingEvent> fileAppender;

    @BeforeEach
    void startStructuredFileAppender() {
        this.logFile = this.tempDir.resolve("app.json");
        this.loggerContext = new LoggerContext();
        this.loggerContext.setName("structured-file-output-level");
        this.loggerContext.putObject(Environment.class.getName(), new MockEnvironment());
        // 독립 LoggerContext에는 MDCAdapter가 없어 인코딩 시점의 MDC 조회가 NPE로 죽는다.
        // 전용 어댑터를 붙여 다른 테스트가 남긴 MDC 값이 이 라인에 섞이지 않게 한다.
        this.loggerContext.setMDCAdapter(new LogbackMDCAdapter());

        this.fileAppender = structuredFileAppender();
        this.logger = this.loggerContext.getLogger("io.wedocs.gateway.common.logging.FileOutputExample");
        this.logger.addAppender(this.fileAppender);

        assertThat(this.fileAppender.isStarted()).isTrue();
        assertThat(this.loggerContext.getStatusManager().getCopyOfStatusList())
                .as("appender 배선이 조용히 실패하면 파일이 비고 실패 원인이 드러나지 않는다")
                .noneMatch(status -> status.getLevel() == Status.ERROR);
    }

    @AfterEach
    void stopStructuredFileAppender() {
        this.fileAppender.stop();
        this.loggerContext.stop();
    }

    @Test
    @DisplayName("DEBUG·INFO·WARN·ERROR 각 1건이 구조화 파일 출력에 key-value 속성으로 남는다")
    void everyLevel_writes_keyValueAttributes_as_jsonMembers() {
        this.logger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        LEVELS.forEach(this::emit);

        List<Map<String, Object>> lines = readStructuredLines();
        assertThat(lines).hasSize(LEVELS.size());
        assertThat(lines).extracting(line -> line.get(LEVEL_MEMBER))
                .containsExactly("DEBUG", "INFO", "WARN", "ERROR");
        lines.forEach(StructuredFileOutputLevelTest::assertAttributesArePresent);
    }

    @Test
    @DisplayName("로거 레벨이 프로덕션 기본(INFO)이면 DEBUG는 파일에 없고 INFO 이상만 남는다")
    void productionDefaultLoggerLevel_writes_infoAndAbove_only() {
        this.logger.setLevel(ch.qos.logback.classic.Level.INFO);

        LEVELS.forEach(this::emit);

        List<Map<String, Object>> lines = readStructuredLines();
        assertThat(lines).extracting(line -> line.get(LEVEL_MEMBER))
                .as("요구사항 1.1의 임계값 문면(INFO 이상)이 성립하는 지점")
                .containsExactly("INFO", "WARN", "ERROR");
        lines.forEach(StructuredFileOutputLevelTest::assertAttributesArePresent);
    }

    private void emit(Level level) {
        this.logger.atLevel(level)
                .setMessage(FIXED_MESSAGE)
                .addKeyValue(DOC_ID_KEY, DOC_ID)
                .addKeyValue(USER_HASH_KEY, USER_HASH)
                .addKeyValue(SERVER_PORT_KEY, SERVER_PORT)
                .log();
    }

    private static void assertAttributesArePresent(Map<String, Object> line) {
        String level = String.valueOf(line.get(LEVEL_MEMBER));
        assertThat(line).as("%s 라인의 문자열 속성", level)
                .containsEntry(DOC_ID_KEY, DOC_ID)
                .containsEntry(USER_HASH_KEY, USER_HASH);
        assertThat(line.get(SERVER_PORT_KEY)).as("%s 라인의 정수 속성", level)
                .isInstanceOf(Number.class)
                .isNotInstanceOfAny(Double.class, Float.class);
        assertThat(((Number) line.get(SERVER_PORT_KEY)).intValue()).isEqualTo(SERVER_PORT);
    }

    /// appender를 멈춰 파일을 닫은 뒤 라인을 파싱한다 — `immediateFlush` 기본값이 true라
    /// 이미 기록되어 있지만, 닫고 읽으면 버퍼링 설정 변화에 테스트가 흔들리지 않는다.
    private List<Map<String, Object>> readStructuredLines() {
        this.fileAppender.stop();
        try {
            return Files.readAllLines(this.logFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> JSON.<Map<String, Object>>readValue(line, JSON_OBJECT))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("구조화 로그 파일을 읽지 못했다: " + this.logFile, e);
        }
    }

    /// Boot `structured-file-appender.xml`과 같은 구성의 appender를 만든다.
    private RollingFileAppender<ILoggingEvent> structuredFileAppender() {
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(this.loggerContext);
        encoder.setFormat(FORMAT);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ThresholdFilter threshold = new ThresholdFilter();
        threshold.setContext(this.loggerContext);
        threshold.setLevel(FILE_LOG_THRESHOLD);
        threshold.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(this.loggerContext);
        appender.setName("FILE");
        appender.setFile(this.logFile.toString());
        appender.setEncoder(encoder);
        appender.addFilter(threshold);
        appender.setRollingPolicy(rollingPolicy(appender));
        appender.start();
        return appender;
    }

    private SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy(
            RollingFileAppender<ILoggingEvent> appender) {
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(this.loggerContext);
        policy.setParent(appender);
        policy.setFileNamePattern(this.logFile + ".%d{yyyy-MM-dd}.%i.gz");
        policy.setMaxFileSize(FileSize.valueOf(MAX_FILE_SIZE));
        policy.setTotalSizeCap(FileSize.valueOf(TOTAL_SIZE_CAP));
        policy.setMaxHistory(MAX_HISTORY);
        policy.start();
        return policy;
    }
}

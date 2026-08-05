package io.wedocs.gateway.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/// 로깅 설정 계약 회귀 가드 (config-contract-audit). `logback-spring.xml`을 없애고 로깅을
/// Spring Boot 표준 프로퍼티로만 구성했으므로, 계약이 깨지는 방식이 "XML 문법 오류"가 아니라
/// "키가 조용히 사라져 Boot 폴백이 적용됨"으로 바뀌었다. 이 테스트가 그 조용한 회귀를 막는다.
///
/// 검증 단위는 **프로파일별로 해석된 프로퍼티 값**이다. appender 객체나 렌더링된 로그 문자열이
/// 아니라 설정 해석 결과를 보는 이유: 요구사항 1.2·1.3이 규정하는 것이 "콘솔이 어떤 포맷으로
/// 해석되는가"이고, 그 판정이 `logging.structured.format.console` 키의 존재·값으로 환원되기 때문이다.
/// (키 부재 = Boot 기본 평문 패턴, `logstash` = 구조화 JSON.)
///
/// 실행 결과 로그 자체의 구조(속성 → JSON 라운드트립)는 별도 property 테스트가 다룬다.
@Tag("Feature: structured-logging-unification-v2")
class LoggingConfigContractTest {

    private static final String FORMAT_CONSOLE = "logging.structured.format.console";
    private static final String FORMAT_FILE = "logging.structured.format.file";
    private static final String FILE_NAME = "logging.file.name";
    private static final String MAX_FILE_SIZE = "logging.logback.rollingpolicy.max-file-size";
    private static final String TOTAL_SIZE_CAP = "logging.logback.rollingpolicy.total-size-cap";
    private static final String MAX_HISTORY = "logging.logback.rollingpolicy.max-history";

    private static final String STRUCTURED_FORMAT = "logstash";
    private static final String STRUCTURED_FILE = "logs/app.json";

    /// Boot `structured-file-appender.xml`의 폴백 값(`${LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE:-10MB}`,
    /// `${LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP:-0}`, `${LOGBACK_ROLLINGPOLICY_MAX_HISTORY:-7}`).
    /// YAML에서 키를 빼면 이 값이 조용히 적용되므로, 명시 설정임을 확인하는 기준선으로 쓴다.
    private static final String BOOT_FALLBACK_MAX_FILE_SIZE = "10MB";
    private static final String BOOT_FALLBACK_TOTAL_SIZE_CAP = "0";

    private static final String LOGGING_PREFIX = "logging.";
    private static final String DOC_SERVICE = "doc-service";
    private static final String WS_GATEWAY = "ws-gateway";
    private static final String MAIN_CONFIG = "src/main/resources/application.yml";
    private static final String PROD_CONFIG = "src/main/resources/application-prod.yml";

    /// 프로파일을 먼저 환경에 심고 그 다음 ConfigData를 적용한다 — 순서가 반대면 profile-specific
    /// 문서(`application-{profile}.yml`)가 로드되지 않아 prod 분기 검증이 공허해진다.
    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner().withInitializer(context -> {
            context.getEnvironment().setActiveProfiles(profiles);
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }

    @Test
    @DisplayName("기본 프로파일: 콘솔은 평문(키 부재), 파일은 구조화 JSON")
    void defaultProfile_keeps_console_plain_and_file_structured() {
        runner().run(context -> {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty(FORMAT_CONSOLE))
                    .as("콘솔 구조화 포맷 키가 있으면 기본 평문 패턴이 아니게 된다(요구사항 1.3)")
                    .isNull();
            assertThat(env.getProperty(FORMAT_FILE)).isEqualTo(STRUCTURED_FORMAT);
            assertThat(env.getProperty(FILE_NAME)).isEqualTo(STRUCTURED_FILE);
        });
    }

    @Test
    @DisplayName("dev 프로파일: 콘솔은 평문 유지, 파일은 구조화 JSON")
    void devProfile_keeps_console_plain_and_file_structured() {
        runner("dev").run(context -> {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty(FORMAT_CONSOLE))
                    .as("dev에 로깅 키를 두지 않는 것이 곧 기본 평문 패턴이다(요구사항 1.3)")
                    .isNull();
            assertThat(env.getProperty(FORMAT_FILE)).isEqualTo(STRUCTURED_FORMAT);
        });
    }

    @Test
    @DisplayName("prod 프로파일: 콘솔까지 구조화 JSON")
    void prodProfile_switches_console_to_structured_json() {
        runner("prod").run(context -> {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty(FORMAT_CONSOLE))
                    .as("prod는 컨테이너 stdout 수집 경로도 구조화한다(요구사항 1.2)")
                    .isEqualTo(STRUCTURED_FORMAT);
            assertThat(env.getProperty(FORMAT_FILE)).isEqualTo(STRUCTURED_FORMAT);
        });
    }

    @Test
    @DisplayName("rolling 프로퍼티 3개는 Boot 폴백이 아니라 명시 설정이다")
    void rollingPolicyProperties_are_explicit_not_boot_fallbacks() {
        runner().run(context -> {
            Environment env = context.getEnvironment();

            // 폴백은 logback include의 `:-기본값` 형태라 환경 프로퍼티로 노출되지 않는다.
            // 따라서 값이 해석된다는 것 자체가 YAML의 명시 설정을 뜻한다.
            assertThat(env.getProperty(MAX_FILE_SIZE)).isEqualTo("20MB");
            assertThat(env.getProperty(TOTAL_SIZE_CAP)).isEqualTo("500MB");
            // max-history는 재산정 결과가 우연히 Boot 폴백(7)과 같다 → 값으로는 명시 여부를
            // 구분할 수 없어 키 존재로만 고정한다.
            assertThat(env.containsProperty(MAX_HISTORY)).isTrue();
            assertThat(env.getProperty(MAX_HISTORY)).isEqualTo("7");

            assertThat(env.getProperty(MAX_FILE_SIZE)).isNotEqualTo(BOOT_FALLBACK_MAX_FILE_SIZE);
            assertThat(env.getProperty(TOTAL_SIZE_CAP)).isNotEqualTo(BOOT_FALLBACK_TOTAL_SIZE_CAP);
        });
    }

    @Test
    @DisplayName("두 모듈의 application.yml 로깅 키·값 집합이 동일하다")
    void bothModules_declare_identical_logging_properties() {
        Map<String, String> gateway = loggingProperties(backendRoot().resolve(WS_GATEWAY).resolve(MAIN_CONFIG));
        Map<String, String> docService = loggingProperties(backendRoot().resolve(DOC_SERVICE).resolve(MAIN_CONFIG));

        assertThat(gateway)
                .as("로깅 블록이 비면 동일성 비교가 공허하게 통과한다")
                .isNotEmpty();
        assertThat(gateway).containsExactlyInAnyOrderEntriesOf(docService);
    }

    @Test
    @DisplayName("두 모듈의 application-prod.yml 로깅 키·값 집합이 동일하다")
    void bothModules_declare_identical_prod_logging_properties() {
        Map<String, String> gateway = loggingProperties(backendRoot().resolve(WS_GATEWAY).resolve(PROD_CONFIG));
        Map<String, String> docService = loggingProperties(backendRoot().resolve(DOC_SERVICE).resolve(PROD_CONFIG));

        assertThat(gateway).isNotEmpty();
        assertThat(gateway).containsExactlyInAnyOrderEntriesOf(docService);
    }

    /// 두 모듈은 서로를 컴파일 의존하지 않으므로(요구사항 10.3) 상대 모듈의 설정을 클래스패스에서
    /// 읽을 방법이 없다. 그래서 두 모듈을 함께 담은 backend 루트를 파일시스템에서 거슬러 찾고,
    /// 형제 모듈 YAML을 직접 로드한다. cwd가 모듈 디렉터리(Gradle)든 리포 루트(IDE)든 동작한다.
    /// 루트를 못 찾으면 예외로 실패한다 — 조용히 skip하면 드리프트가 무검증으로 통과한다.
    private static Path backendRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            boolean hasBothModules = Files.isDirectory(dir.resolve(DOC_SERVICE))
                    && Files.isDirectory(dir.resolve(WS_GATEWAY));
            if (hasBothModules) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "doc-service·ws-gateway를 함께 담은 backend 루트를 찾지 못했다: " + Path.of("").toAbsolutePath());
    }

    /// Boot 자신의 `YamlPropertySourceLoader`로 평면화한다 — 런타임이 YAML을 프로퍼티로 접는 규칙과
    /// 같은 규칙을 써야 "키·값 집합 동일"이 런타임 해석의 동일성을 뜻하게 된다.
    private static Map<String, String> loggingProperties(Path yaml) {
        Map<String, String> flattened = new TreeMap<>();
        for (PropertySource<?> source : load(yaml)) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith(LOGGING_PREFIX)) {
                    flattened.put(name, String.valueOf(enumerable.getProperty(name)));
                }
            }
        }
        return flattened;
    }

    private static List<PropertySource<?>> load(Path yaml) {
        try {
            return new YamlPropertySourceLoader().load(yaml.toString(), new FileSystemResource(yaml));
        } catch (IOException e) {
            throw new UncheckedIOException("로깅 설정 YAML을 읽지 못했다: " + yaml, e);
        }
    }
}

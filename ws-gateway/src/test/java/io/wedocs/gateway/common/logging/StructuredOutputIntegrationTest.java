package io.wedocs.gateway.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

/// 구조화 출력 통합 스모크 테스트 — Spring Boot의 로깅 시스템이 application.yml의 `logging.*`
/// 프로퍼티를 해석해 FILE appender를 실제로 배선하고, `logs/app.json`에 구조화 JSON이
/// 기록되는지 확인한다. 파일 경로·appender 임계값 변경의 회귀 방지(요구사항 1.1, 10.7).
///
/// `@SpringBootTest`를 쓰지 않는 이유: ws-gateway의 다른 통합 테스트(`AbstractWsIntegrationTest`)가
/// `WsBackends`(package-private static gRPC 대역)와 고정 포트를 공유하는 단일 컨텍스트를 쓴다.
/// 별도 `@SpringBootTest`를 추가하면 컨텍스트 캐시 키가 갈리면서 정적 포트 바인딩이 충돌한다.
///
/// 여기서는 `SpringApplicationBuilder`로 NONE 모드(서블릿 없음) 최소 컨텍스트를 기동한다.
/// 빈 `@Configuration`만 소스로 쓰고 auto-configuration·component-scan을 켜지 않는다.
/// Boot의 `LoggingApplicationListener`는 `SpringApplication` 이벤트 리스너라
/// auto-configuration 없이도 `application.yml`의 로깅 프로퍼티를 Logback에 적용한다.
@Tag("Feature: structured-logging-unification-v2")
class StructuredOutputIntegrationTest {

    private static final Path LOG_FILE = Path.of("logs/app.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /// Boot 로깅 리스너만 동작시키기 위한 빈 설정. component-scan이나 auto-config 없음.
    @Configuration
    static class EmptyConfig {
    }

    @Test
    @DisplayName("Boot 로깅이 application.yml로 배선한 FILE appender에서 INFO 이벤트가 logs/app.json에 구조화 JSON으로 기록된다")
    void bootLogging_writesStructuredJsonToLogFile() throws IOException {
        // 고유 마커로 이 테스트의 라인을 식별 — 다른 테스트·기동 로그와 섞여도 판별 가능
        String marker = "gateway-smoke-" + UUID.randomUUID();

        // Spring Boot를 NONE 모드로 기동 — application.yml에서 logging.* 프로퍼티를 읽어
        // LoggingApplicationListener가 Logback FILE appender를 배선한다.
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(EmptyConfig.class)
                .web(NONE)
                .run()) {

            // When: LogEvents emitter를 통해 INFO 이벤트 emit
            Logger logger = LoggerFactory.getLogger(StructuredOutputIntegrationTest.class);
            LogEvents.event(logger, GatewayLogEvent.HANDSHAKE_OK)
                    .attr(LogFields.DOC_ID, marker)
                    .attr(LogFields.USER_HASH, "test_hash_00")
                    .log();
        }

        // Then: logs/app.json이 존재하고, 마커를 담은 라인이 유효한 구조화 JSON이다
        assertThat(LOG_FILE).exists();

        List<Map<String, Object>> matchingLines = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JSON.<Map<String, Object>>readValue(line, JSON_OBJECT))
                .filter(json -> marker.equals(json.get(LogFields.DOC_ID)))
                .toList();

        assertThat(matchingLines)
                .as("마커(%s)를 담은 구조화 라인이 정확히 1건 있어야 한다", marker)
                .hasSize(1);

        Map<String, Object> logLine = matchingLines.getFirst();

        // 필수 구조화 멤버 존재 확인
        assertThat(logLine).containsKey("@timestamp");
        assertThat(logLine).containsKey("level");
        assertThat(logLine.get("level")).isEqualTo("INFO");

        // event.name 속성이 KVP로 기록됨
        assertThat(logLine).containsEntry(LogFields.EVENT_NAME, GatewayLogEvent.HANDSHAKE_OK.eventName());

        // user.hash 속성
        assertThat(logLine).containsEntry(LogFields.USER_HASH, "test_hash_00");
    }
}

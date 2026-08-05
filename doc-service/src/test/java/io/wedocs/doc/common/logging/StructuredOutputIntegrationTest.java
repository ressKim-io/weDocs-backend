package io.wedocs.doc.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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

/// 구조화 출력 통합 스모크 테스트 — Spring 컨텍스트 기동 후 `logs/app.json`에 구조화 JSON이
/// 실제로 기록되는지 확인한다. 파일 경로·appender 임계값 변경의 회귀 방지(요구사항 1.1, 10.7).
///
/// doc-service는 `spring.datasource.url`이 필수이므로 Testcontainers로 PostgreSQL을 띄운다.
/// `@ServiceConnection`이 datasource 프로퍼티를 자동 주입한다(AuthFlowIntegrationTest와 동일 패턴).
///
/// 이 테스트는 `StructuredFileOutputLevelTest`(task 1.4)와 다른 관심사를 다룬다:
/// - task 1.4: 프로그래밍적으로 배선한 appender의 레벨별 출력
/// - 여기(task 12.3): Spring Boot가 application.yml로 실제 배선한 appender가 파일을 생성하는가
@Tag("Feature: structured-logging-unification-v2")
@SpringBootTest
@Testcontainers
class StructuredOutputIntegrationTest {

    private static final Path LOG_FILE = Path.of("logs/app.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("컨텍스트 기동 후 LogEvents로 emit한 INFO 이벤트가 logs/app.json에 구조화 JSON으로 기록된다")
    void contextBoot_emitsStructuredJsonToLogFile() throws IOException {
        // Given: 컨텍스트가 이미 기동됨 (Spring Boot auto-config가 FILE appender를 배선)
        // 고유 마커로 이 테스트의 라인을 식별한다 — 다른 테스트·기동 로그와 섞여도 판별 가능
        String marker = "smoke-" + UUID.randomUUID();
        Logger logger = LoggerFactory.getLogger(StructuredOutputIntegrationTest.class);

        // When: LogEvents emitter를 통해 INFO 이벤트 emit
        LogEvents.event(logger, DocLogEvent.OUTBOX_CLEANUP_COMPLETED)
                .attr(LogFields.OUTBOX_PUBLISHED_DELETED, 0L)
                .attr(LogFields.OUTBOX_UNPUBLISHED_DELETED, 0L)
                .attr(LogFields.USER_HASH, marker)
                .log();

        // Then: logs/app.json이 존재하고, 마커를 담은 라인이 유효한 구조화 JSON이다
        assertThat(LOG_FILE).exists();

        List<Map<String, Object>> matchingLines = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JSON.<Map<String, Object>>readValue(line, JSON_OBJECT))
                .filter(json -> marker.equals(json.get(LogFields.USER_HASH)))
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
        assertThat(logLine).containsEntry(LogFields.EVENT_NAME, DocLogEvent.OUTBOX_CLEANUP_COMPLETED.eventName());

        // 정수 속성이 JSON 수 리터럴로 남음
        assertThat(logLine.get(LogFields.OUTBOX_PUBLISHED_DELETED))
                .isInstanceOf(Number.class);
    }
}

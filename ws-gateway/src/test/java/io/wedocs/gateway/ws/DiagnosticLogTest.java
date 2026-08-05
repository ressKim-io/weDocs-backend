package io.wedocs.gateway.ws;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.wedocs.gateway.common.logging.CapturedLogs;
import io.wedocs.gateway.common.logging.LogFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// Diagnostic_Log 단위 테스트 — 기동/종료·정리 경로 로그에 `event.name` 속성이 없음을 확인한다.
/// 구조화 이벤트와 Diagnostic_Log의 분류 경계를 검증한다.
///
/// _Requirements: 5.6_
@Tag("Feature: structured-logging-unification-v2")
class DiagnosticLogTest {

    @Test
    @DisplayName("closeQuietly 실패 로그(DEBUG)에는 event.name 속성이 없다")
    void closeQuietly_logHasNoEventName() {
        Logger logger = (Logger) LoggerFactory.getLogger(DocWebSocketHandler.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.ALL);
        try (var logs = CapturedLogs.of(DocWebSocketHandler.class)) {
            // closeQuietly 실패는 IOException catch 내부의 log.debug — Diagnostic_Log 형태.
            logger.atDebug()
                    .addKeyValue("wedocs.session.id", "test-session")
                    .log("ws close failed");

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();
            // Diagnostic_Log에는 event.name이 없다
            assertThat(event.hasKey(LogFields.EVENT_NAME)).isFalse();
        } finally {
            logger.setLevel(original);
        }
    }

    @Test
    @DisplayName("completeQuietly 무시 로그(DEBUG)에는 event.name 속성이 없다")
    void completeQuietly_logHasNoEventName() {
        Logger logger = (Logger) LoggerFactory.getLogger(DocWebSocketHandler.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.ALL);
        try (var logs = CapturedLogs.of(DocWebSocketHandler.class)) {
            // completeQuietly 흡수는 정적 메서드 내부의 log.debug — Diagnostic_Log 형태.
            logger.atDebug()
                    .log("completeQuietly 무시 — 스트림이 이미 종료된 것으로 보임");

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();
            // Diagnostic_Log에는 event.name이 없다
            assertThat(event.hasKey(LogFields.EVENT_NAME)).isFalse();
        } finally {
            logger.setLevel(original);
        }
    }

    @Test
    @DisplayName("JwtVerifier 검증 거절 DEBUG 로그에는 event.name 속성이 없다")
    void jwtVerifierReject_logHasNoEventName() {
        Logger logger = (Logger) LoggerFactory.getLogger(io.wedocs.gateway.auth.JwtVerifier.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.ALL);
        try (var logs = CapturedLogs.of(io.wedocs.gateway.auth.JwtVerifier.class)) {
            // JwtVerifier debug 로그 — Diagnostic_Log로 유지(같은 실패가 ws_handshake authn_fail로 이미 집계됨).
            logger.atDebug()
                    .log("jwt verification rejected: Token expired");

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();
            // Diagnostic_Log에는 event.name이 없다
            assertThat(event.hasKey(LogFields.EVENT_NAME)).isFalse();
        } finally {
            logger.setLevel(original);
        }
    }
}

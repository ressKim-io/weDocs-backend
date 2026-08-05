package io.wedocs.doc.common.logging;

import ch.qos.logback.classic.Logger;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 6.5, 9.3**
///
/// Property 3: 선언 레벨 일치 —
/// 모든 Event_Taxonomy 엔트리에 대해, emitter가 산출한 로그 이벤트의 레벨이
/// taxonomy가 선언한 레벨과 정확히 일치함을 검증한다.
/// emitter(`LogEvents`)는 `taxonomy.level()`을 `createBuilder`에 전달해 레벨을 설정하므로,
/// 이 property는 그 경로가 모든 엔트리에서 올바르게 동작함을 확인한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 3: 선언 레벨 일치")
class EmitterDeclaredLevelPropertyTest {

    /// SLF4J Level → Logback Level 변환. CapturedEvent.level()은 Logback Level을 반환하고,
    /// taxonomy는 SLF4J Level을 선언하므로 비교를 위해 변환이 필요하다.
    private static ch.qos.logback.classic.Level toLogbackLevel(org.slf4j.event.Level slf4jLevel) {
        return switch (slf4jLevel) {
            case ERROR -> ch.qos.logback.classic.Level.ERROR;
            case WARN -> ch.qos.logback.classic.Level.WARN;
            case INFO -> ch.qos.logback.classic.Level.INFO;
            case DEBUG -> ch.qos.logback.classic.Level.DEBUG;
            case TRACE -> ch.qos.logback.classic.Level.TRACE;
        };
    }

    @Property(tries = 100)
    void allTaxonomyEntries_emitAtDeclaredLevel() {
        // 로거 레벨을 ALL로 설정해 DEBUG/TRACE 이벤트도 캡처되게 한다.
        Logger logger = (Logger) LoggerFactory.getLogger(EmitterDeclaredLevelPropertyTest.class);
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.ALL);

        try {
            for (DocLogEvent taxonomy : DocLogEvent.values()) {
                try (var logs = CapturedLogs.of(EmitterDeclaredLevelPropertyTest.class)) {
                    // 필수 속성을 더미 값으로 채워 emit이 실패하지 않게 한다.
                    LogEvents emitter = LogEvents.event(logger, taxonomy);
                    for (String requiredAttr : taxonomy.requiredAttributes()) {
                        if (requiredAttr.equals(LogFields.ERROR_TYPE)) {
                            emitter = emitter.errorType(DocLogErrorType.MALFORMED_ID);
                        } else {
                            emitter = emitter.attr(requiredAttr, "dummy-value");
                        }
                    }
                    emitter.log();

                    // 캡처된 이벤트의 레벨이 taxonomy 선언 레벨과 일치하는지 확인
                    assertThat(logs.events())
                            .as("taxonomy '%s' must emit exactly 1 event", taxonomy.name())
                            .hasSize(1);

                    ch.qos.logback.classic.Level actualLevel = logs.events().getFirst().level();
                    ch.qos.logback.classic.Level expectedLevel = toLogbackLevel(taxonomy.level());

                    assertThat(actualLevel)
                            .as("taxonomy '%s' declared level=%s but emitted level=%s",
                                    taxonomy.name(), taxonomy.level(), actualLevel)
                            .isEqualTo(expectedLevel);
                }
            }
        } finally {
            logger.setLevel(originalLevel);
        }
    }
}

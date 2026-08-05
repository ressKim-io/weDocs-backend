package io.wedocs.doc.common.logging;

import ch.qos.logback.classic.Logger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Tag;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 2.2, 2.3, 9.4**
///
/// Property 14: 메시지 독립성 —
/// 같은 taxonomy 엔트리를 동일 속성으로 두 번 emit하면 KVP 키 집합·값·레벨이 동일하고,
/// 메시지는 taxonomy가 소유한 고정 문구임을 확인한다. 구조적 출력은 taxonomy 엔트리와
/// 전달된 속성에만 의존하고, emit 순서나 기타 상태에 의존하지 않는다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 14: 메시지 독립성")
class MessageIndependencePropertyTest {

    @Property(tries = 100)
    void sameTaxonomySameAttributes_produceIdenticalOutput(
            @ForAll("taxonomyWithAttributes") TaxonomyWithAttrs input) {

        Logger logger = (Logger) LoggerFactory.getLogger(MessageIndependencePropertyTest.class);
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.ALL);

        try {
            List<CapturedLogs.CapturedEvent> firstEmit;
            List<CapturedLogs.CapturedEvent> secondEmit;

            // First emit
            try (var logs = CapturedLogs.of(MessageIndependencePropertyTest.class)) {
                emitWithAttributes(logger, input.taxonomy(), input.attributes());
                firstEmit = logs.events();
            }

            // Second emit — same taxonomy, same attributes
            try (var logs = CapturedLogs.of(MessageIndependencePropertyTest.class)) {
                emitWithAttributes(logger, input.taxonomy(), input.attributes());
                secondEmit = logs.events();
            }

            assertThat(firstEmit).hasSize(1);
            assertThat(secondEmit).hasSize(1);

            var event1 = firstEmit.getFirst();
            var event2 = secondEmit.getFirst();

            // Key sets must be identical
            List<String> keys1 = event1.kvp().stream().map(p -> p.key).toList();
            List<String> keys2 = event2.kvp().stream().map(p -> p.key).toList();
            assertThat(keys1)
                    .as("taxonomy '%s': KVP key sets must be identical across two emits",
                            input.taxonomy().name())
                    .isEqualTo(keys2);

            // Values for each key must be identical
            Map<String, Object> values1 = event1.kvp().stream()
                    .collect(Collectors.toMap(p -> p.key, p -> p.value, (a, b) -> a));
            Map<String, Object> values2 = event2.kvp().stream()
                    .collect(Collectors.toMap(p -> p.key, p -> p.value, (a, b) -> a));
            assertThat(values1)
                    .as("taxonomy '%s': KVP values must be identical across two emits",
                            input.taxonomy().name())
                    .isEqualTo(values2);

            // Levels must be identical
            assertThat(event1.level())
                    .as("taxonomy '%s': level must be identical across two emits",
                            input.taxonomy().name())
                    .isEqualTo(event2.level());
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Property(tries = 100)
    void allTaxonomyEntries_messageIsFixedFromTaxonomy() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageIndependencePropertyTest.class);
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.ALL);

        try {
            for (DocLogEvent taxonomy : DocLogEvent.values()) {
                try (var logs = CapturedLogs.of(MessageIndependencePropertyTest.class)) {
                    LogEvents emitter = LogEvents.event(logger, taxonomy);
                    for (String requiredAttr : taxonomy.requiredAttributes()) {
                        if (requiredAttr.equals(LogFields.ERROR_TYPE)) {
                            emitter = emitter.errorType(DocLogErrorType.MALFORMED_ID);
                        } else {
                            emitter = emitter.attr(requiredAttr, "test-value");
                        }
                    }
                    emitter.log();

                    // Message is the fixed text from taxonomy
                    var rawEvents = logs.rawEvents();
                    assertThat(rawEvents).hasSize(1);
                    assertThat(rawEvents.getFirst().getMessage())
                            .as("taxonomy '%s': message must equal taxonomy.message()",
                                    taxonomy.name())
                            .isEqualTo(taxonomy.message());
                }
            }
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Provide
    Arbitrary<TaxonomyWithAttrs> taxonomyWithAttributes() {
        return Arbitraries.of(DocLogEvent.values()).flatMap(taxonomy -> {
            // Generate attribute values for all required attributes
            Arbitrary<Map<String, String>> attrsArb = generateAttributes(taxonomy);
            return attrsArb.map(attrs -> new TaxonomyWithAttrs(taxonomy, attrs));
        });
    }

    private Arbitrary<Map<String, String>> generateAttributes(DocLogEvent taxonomy) {
        if (taxonomy.requiredAttributes().isEmpty()) {
            return Arbitraries.just(Map.of());
        }

        // Build a map with generated values for each required attribute
        List<String> keys = List.copyOf(taxonomy.requiredAttributes());
        Arbitrary<String> valueArb = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20);

        return valueArb.list().ofSize(keys.size()).map(values -> {
            var builder = new java.util.HashMap<String, String>();
            for (int i = 0; i < keys.size(); i++) {
                builder.put(keys.get(i), values.get(i));
            }
            return Map.copyOf(builder);
        });
    }

    private void emitWithAttributes(Logger logger, DocLogEvent taxonomy,
                                    Map<String, String> attributes) {
        LogEvents emitter = LogEvents.event(logger, taxonomy);
        for (var entry : attributes.entrySet()) {
            if (entry.getKey().equals(LogFields.ERROR_TYPE)) {
                emitter = emitter.errorType(DocLogErrorType.MALFORMED_ID);
            } else {
                emitter = emitter.attr(entry.getKey(), entry.getValue());
            }
        }
        emitter.log();
    }

    record TaxonomyWithAttrs(DocLogEvent taxonomy, Map<String, String> attributes) {
    }
}

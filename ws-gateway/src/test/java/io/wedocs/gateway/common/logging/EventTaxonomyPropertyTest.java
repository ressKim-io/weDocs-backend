package io.wedocs.gateway.common.logging;

import java.util.regex.Pattern;

import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 5.4, 5.5, 5.7**
///
/// Property 15: 이벤트 이름 닫힌 집합과 선언 완전성 —
/// GatewayLogEvent enum 전수에 대해 eventName이 snake_case(2세그먼트 이상)를 만족하고,
/// level과 requiredAttributes 선언이 존재하며, 속성 키가 dot-notation 규약을 준수함을 검증한다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 15: 이벤트 이름 닫힌 집합과 선언 완전성")
class EventTaxonomyPropertyTest {

    /// snake_case, 최소 2개 세그먼트: `[a-z0-9]+(_[a-z0-9]+)+`
    private static final Pattern SNAKE_CASE_TWO_SEGMENTS =
            Pattern.compile("^[a-z0-9]+(_[a-z0-9]+)+$");

    /// dot-notation: `[a-z][a-z0-9_]*(.[a-z][a-z0-9_]*)*`
    private static final Pattern DOT_NOTATION =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    @Property(tries = 100)
    void allEventNames_matchSnakeCaseWithAtLeastTwoSegments() {
        for (GatewayLogEvent event : GatewayLogEvent.values()) {
            assertThat(SNAKE_CASE_TWO_SEGMENTS.matcher(event.eventName()).matches())
                    .as("event '%s' eventName '%s' must be snake_case with ≥2 segments",
                            event.name(), event.eventName())
                    .isTrue();
        }
    }

    @Property(tries = 100)
    void allEntries_haveLevelDeclared() {
        for (GatewayLogEvent event : GatewayLogEvent.values()) {
            assertThat(event.level())
                    .as("event '%s' must have a non-null level", event.name())
                    .isNotNull();
        }
    }

    @Property(tries = 100)
    void allEntries_haveNonEmptyRequiredAttributes() {
        for (GatewayLogEvent event : GatewayLogEvent.values()) {
            assertThat(event.requiredAttributes())
                    .as("event '%s' must have non-null, non-empty requiredAttributes", event.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Property(tries = 100)
    void allRequiredAttributes_matchDotNotationRegex() {
        for (GatewayLogEvent event : GatewayLogEvent.values()) {
            for (String attr : event.requiredAttributes()) {
                assertThat(DOT_NOTATION.matcher(attr).matches())
                        .as("event '%s' attribute '%s' must match dot-notation regex",
                                event.name(), attr)
                        .isTrue();
            }
        }
    }
}

package io.wedocs.doc.common.logging;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// **Validates: Requirements 7.6, 7.7**
///
/// Property 11: PII 비노출 —
/// 임의 토큰·JWT·이메일·UUID 입력에 대해 LogMasker.mask()를 거쳐 LogEvents로 emit하면
/// (1) 어떤 속성 값도 원문 PII를 포함하지 않고
/// (2) 금지 키(user.id, user.email, user.full_name)가 KVP 키에 나타나지 않는다.
@Tag("Feature: structured-logging-unification-v2")
@Tag("Property 11: PII 비노출")
class PiiNonExposurePropertyTest {

    private static final Logger logger = LoggerFactory.getLogger(PiiNonExposurePropertyTest.class);

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "user.id", "user.email", "user.full_name"
    );

    // ── PII non-exposure: masked value never contains raw input ──

    @Property(tries = 100)
    void maskedToken_neverExposesRawValue(@ForAll("randomTokens") String token) {
        String masked = LogMasker.mask(token);

        try (var logs = CapturedLogs.of(PiiNonExposurePropertyTest.class)) {
            LogEvents.event(logger, DocLogEvent.WORKSPACE_LIST_CAPPED)
                    .attr(LogFields.USER_HASH, masked)
                    .attr(LogFields.WORKSPACE_LIST_CAP, 100L)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            // No KVP value contains the raw token
            for (var pair : event.kvp()) {
                assertThat(String.valueOf(pair.value))
                        .as("KVP key '%s' must not contain raw token", pair.key)
                        .doesNotContain(token);
            }
        }
    }

    @Property(tries = 100)
    void maskedJwt_neverExposesRawValue(@ForAll("jwtStrings") String jwt) {
        String masked = LogMasker.mask(jwt);

        try (var logs = CapturedLogs.of(PiiNonExposurePropertyTest.class)) {
            LogEvents.event(logger, DocLogEvent.WORKSPACE_LIST_CAPPED)
                    .attr(LogFields.USER_HASH, masked)
                    .attr(LogFields.WORKSPACE_LIST_CAP, 100L)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            for (var pair : event.kvp()) {
                assertThat(String.valueOf(pair.value))
                        .as("KVP key '%s' must not contain raw JWT", pair.key)
                        .doesNotContain(jwt);
            }
        }
    }

    @Property(tries = 100)
    void maskedEmail_neverExposesLocalPartOrDomain(@ForAll("emailAddresses") String email) {
        String masked = LogMasker.mask(email);
        String localPart = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@') + 1);

        try (var logs = CapturedLogs.of(PiiNonExposurePropertyTest.class)) {
            LogEvents.event(logger, DocLogEvent.WORKSPACE_LIST_CAPPED)
                    .attr(LogFields.USER_HASH, masked)
                    .attr(LogFields.WORKSPACE_LIST_CAP, 100L)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            for (var pair : event.kvp()) {
                String valueStr = String.valueOf(pair.value);
                assertThat(valueStr)
                        .as("KVP key '%s' must not contain email local-part '%s'", pair.key, localPart)
                        .doesNotContain(localPart);
                assertThat(valueStr)
                        .as("KVP key '%s' must not contain email domain '%s'", pair.key, domain)
                        .doesNotContain(domain);
            }
        }
    }

    @Property(tries = 100)
    void maskedUuid_neverExposesRawValue(@ForAll("uuidStrings") String uuid) {
        String masked = LogMasker.mask(uuid);

        try (var logs = CapturedLogs.of(PiiNonExposurePropertyTest.class)) {
            LogEvents.event(logger, DocLogEvent.WORKSPACE_LIST_CAPPED)
                    .attr(LogFields.USER_HASH, masked)
                    .attr(LogFields.WORKSPACE_LIST_CAP, 100L)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            for (var pair : event.kvp()) {
                assertThat(String.valueOf(pair.value))
                        .as("KVP key '%s' must not contain raw UUID", pair.key)
                        .doesNotContain(uuid);
            }
        }
    }

    // ── Forbidden keys never appear ──

    @Property(tries = 100)
    void forbiddenKeys_neverAppearInKvp(@ForAll("piiValues") String piiValue) {
        String masked = LogMasker.mask(piiValue);

        try (var logs = CapturedLogs.of(PiiNonExposurePropertyTest.class)) {
            LogEvents.event(logger, DocLogEvent.WORKSPACE_LIST_CAPPED)
                    .attr(LogFields.USER_HASH, masked)
                    .attr(LogFields.WORKSPACE_LIST_CAP, 100L)
                    .log();

            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();

            Set<String> kvpKeys = event.kvp().stream()
                    .map(p -> p.key)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(kvpKeys)
                    .as("KVP keys must not contain any forbidden PII key")
                    .doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
    }

    // ── Arbitraries ──

    @Provide
    Arbitrary<String> randomTokens() {
        return Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(20)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> jwtStrings() {
        // JWT-like: header.payload.signature (base64url-like segments)
        Arbitrary<String> segment = Arbitraries.strings()
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")
                .ofMinLength(10)
                .ofMaxLength(40);
        return Combinators.combine(segment, segment, segment)
                .as((header, payload, sig) -> "eyJhbGciOiJSUzI1NiJ9." + payload + "." + sig);
    }

    @Provide
    Arbitrary<String> emailAddresses() {
        Arbitrary<String> localPart = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789._%+-")
                .ofMinLength(3)
                .ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789-")
                .ofMinLength(3)
                .ofMaxLength(15);
        Arbitrary<String> tld = Arbitraries.of("com", "org", "net", "io", "dev");
        return Combinators.combine(localPart, domain, tld)
                .as((local, dom, ext) -> local + "@" + dom + "." + ext);
    }

    @Provide
    Arbitrary<String> uuidStrings() {
        Arbitrary<String> hex8 = Arbitraries.strings()
                .withChars("0123456789abcdef").ofMinLength(8).ofMaxLength(8);
        Arbitrary<String> hex4 = Arbitraries.strings()
                .withChars("0123456789abcdef").ofMinLength(4).ofMaxLength(4);
        Arbitrary<String> hex12 = Arbitraries.strings()
                .withChars("0123456789abcdef").ofMinLength(12).ofMaxLength(12);
        return Combinators.combine(hex8, hex4, hex4, hex4, hex12)
                .as((a, b, c, d, e) -> a + "-" + b + "-" + c + "-" + d + "-" + e);
    }

    @Provide
    Arbitrary<String> piiValues() {
        return Arbitraries.oneOf(randomTokens(), jwtStrings(), emailAddresses(), uuidStrings());
    }
}

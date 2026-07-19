package io.wedocs.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/// RS256 JWT 검증 — doc-service(JwtTokenService)가 발급하는 토큰 형태(sub/iss/exp, kid=thumbprint)를
/// 기준으로 정상/만료/발급자불일치/서명불일치/kid부재/subject부재/형식오류를 확정적으로 검증한다.
class JwtVerifierTest {

    private static final String ISSUER = "wedocs-doc-service";
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";

    private static RSAKey signingKey;      // JWKS에 실린 키(검증 통과 대상)
    private static RSAKey foreignKey;       // JWKS에 없는 키(kid 부재 → 거절 대상)
    private static JwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        foreignKey = new RSAKeyGenerator(2048).keyID("foreign-kid").generate();
        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        verifier = JwtVerifier.fromKeySource(keySource, ISSUER, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("유효한 토큰은 subject(user_id)를 돌려준다")
    void verifySubject_returnsSubjectForValidToken() throws Exception {
        // Given: 발급자·유효기간·서명이 모두 올바른 토큰
        String token = sign(signingKey, baseClaims().build());

        // When/Then
        assertThat(verifier.verifySubject(token)).contains(SUBJECT);
    }

    @Test
    @DisplayName("만료된 토큰은 거절된다(empty)")
    void verifySubject_rejectsExpired() throws Exception {
        // Given: exp가 과거(clock skew 60s보다 더 과거)
        Instant now = Instant.now();
        JWTClaimsSet expired = new JWTClaimsSet.Builder()
                .subject(SUBJECT).issuer(ISSUER)
                .issueTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.minusSeconds(300)))
                .build();

        // When/Then
        assertThat(verifier.verifySubject(sign(signingKey, expired))).isEmpty();
    }

    @Test
    @DisplayName("발급자가 다르면 거절된다")
    void verifySubject_rejectsWrongIssuer() throws Exception {
        // Given: iss가 기대값과 다름
        JWTClaimsSet wrongIssuer = baseClaims().issuer("evil-issuer").build();

        // When/Then
        assertThat(verifier.verifySubject(sign(signingKey, wrongIssuer))).isEmpty();
    }

    @Test
    @DisplayName("JWKS에 없는 kid로 서명한 토큰은 거절된다(unknown kid)")
    void verifySubject_rejectsUnknownKey() throws Exception {
        // Given: 검증기 JWKS에 없는 키(다른 kid)로 서명
        String token = sign(foreignKey, baseClaims().build());

        // When/Then
        assertThat(verifier.verifySubject(token)).isEmpty();
    }

    @Test
    @DisplayName("서명이 위조(변조)된 토큰은 거절된다")
    void verifySubject_rejectsTampered() throws Exception {
        // Given: 유효 토큰의 payload 세그먼트를 훼손
        String valid = sign(signingKey, baseClaims().build());
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x" + "." + parts[2];

        // When/Then
        assertThat(verifier.verifySubject(tampered)).isEmpty();
    }

    @Test
    @DisplayName("subject가 없는 토큰은 거절된다(필수 클레임)")
    void verifySubject_rejectsMissingSubject() throws Exception {
        // Given: sub 없이 발급
        Instant now = Instant.now();
        JWTClaimsSet noSubject = new JWTClaimsSet.Builder()
                .issuer(ISSUER).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300))).build();

        // When/Then
        assertThat(verifier.verifySubject(sign(signingKey, noSubject))).isEmpty();
    }

    @Test
    @DisplayName("JWT 형식이 아닌 문자열은 거절된다")
    void verifySubject_rejectsMalformed() {
        // Given/When/Then
        assertThat(verifier.verifySubject("not-a-jwt")).isEmpty();
        assertThat(verifier.verifySubject("")).isEmpty();
    }

    private static JWTClaimsSet.Builder baseClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(SUBJECT).issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}

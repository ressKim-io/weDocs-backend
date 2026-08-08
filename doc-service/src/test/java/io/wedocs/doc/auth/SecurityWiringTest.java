package io.wedocs.doc.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4.x: 테스트 슬라이스 모듈 분리 — @WebMvcTest 패키지가 spring-boot-webmvc-test로 이동(실측).
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// 보안 배선 슬라이스 검증 — DB 없이 SecurityFilterChain + JWT 자가 검증만.
/// 전체 플로우(signup→login→보호 자원)는 AuthFlowIntegrationTest(Testcontainers).
@WebMvcTest(JwksController.class)
@Import({SecurityConfig.class, JwtConfig.class})
class SecurityWiringTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("JWKS 엔드포인트는 무인증 공개 — 공개키만, 개인키 자료 없음")
    void jwks_isPublicAndExposesNoPrivateMaterial() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    @DisplayName("토큰 없는 요청은 401 — 기본 거부(P3)")
    void withoutToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효 토큰은 인증을 통과한다 — 미매핑 경로라 404(401 아님)")
    void withValidToken_passesAuthentication() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(jwtEncoder, jwtProperties, Clock.systemUTC());
        String token = tokenService
                .issue(new User(UUID.randomUUID(), "wire@test.io", "hash", "wire", SystemRole.USER))
                .accessToken();

        mockMvc.perform(get("/api/anything").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sub 누락 또는 UUID가 아닌 정상 서명 토큰은 401")
    void invalidSubject_unauthorized() throws Exception {
        mockMvc.perform(get("/api/anything")
                        .header("Authorization", "Bearer " + signedToken(null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/anything")
                        .header("Authorization", "Bearer " + signedToken("not-a-uuid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정상 서명·UUID sub여도 issuer가 다르면 401")
    void wrongIssuer_unauthorized() throws Exception {
        String token = signedToken(UUID.randomUUID().toString(), "https://other-issuer.invalid");

        mockMvc.perform(get("/api/anything").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("위조 토큰(서명 불일치)은 401")
    void withForgedToken_unauthorized() throws Exception {
        // 다른 키로 서명한 토큰 — 우리 공개키 검증에서 거부돼야 한다
        JwtConfig otherConfig = new JwtConfig();
        JwtKeys otherKeys = new JwtKeys(null);
        JwtTokenService forger = new JwtTokenService(
                otherConfig.jwtEncoder(otherKeys),
                new JwtProperties("", Duration.ofHours(24), jwtProperties.issuer()),
                Clock.systemUTC());
        String forged = forger
                .issue(new User(UUID.randomUUID(), "forge@test.io", "hash", "forge", SystemRole.USER))
                .accessToken();

        mockMvc.perform(get("/api/anything").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    private String signedToken(String subject) {
        return signedToken(subject, jwtProperties.issuer());
    }

    private String signedToken(String subject, String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)));
        if (subject != null) {
            claims.subject(subject);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}

package io.wedocs.doc.auth;

import com.nimbusds.jwt.SignedJWT;
import io.wedocs.doc.domain.SystemRole;
import io.wedocs.doc.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String ISSUER = "wedocs-doc-service";

    private final JwtConfig config = new JwtConfig();
    private JwtKeys keys;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        keys = new JwtKeys(null);
        decoder = config.jwtDecoder(keys, properties());
    }

    @Test
    @DisplayName("발급한 토큰을 자가 검증 디코더가 수용한다 — sub/iss/system_role/TTL")
    void issue_roundTrip() {
        // Given
        User user = user(SystemRole.USER);
        JwtTokenService service = service(Clock.systemUTC());

        // When
        JwtTokenService.IssuedToken issued = service.issue(user);

        // Then
        Jwt jwt = decoder.decode(issued.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        // getIssuer()는 URL 변환 접근자 — issuer가 URI 형식이 아닌 평문 식별자(RFC 7519 StringOrURI)라 클레임 문자열로 단언
        assertThat(jwt.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(ISSUER);
        assertThat(jwt.getClaimAsString("system_role")).isEqualTo("user");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(TTL);
        assertThat(issued.expiresInSeconds()).isEqualTo(TTL.toSeconds());
    }

    @Test
    @DisplayName("헤더에 kid가 실린다 — Phase 2 gateway·Istio가 JWKS에서 키를 찾는 단서")
    void issue_includesKidHeader() throws Exception {
        // Given
        JwtTokenService service = service(Clock.systemUTC());

        // When
        JwtTokenService.IssuedToken issued = service.issue(user(SystemRole.USER));

        // Then
        SignedJWT parsed = SignedJWT.parse(issued.accessToken());
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(keys.signingKey().getKeyID());
    }

    @Test
    @DisplayName("system_admin 사용자는 system_role 클레임이 system_admin이다 (ADR-0016)")
    void issue_systemAdminClaim() {
        // Given
        User admin = user(SystemRole.SYSTEM_ADMIN);
        JwtTokenService service = service(Clock.systemUTC());

        // When
        JwtTokenService.IssuedToken issued = service.issue(admin);

        // Then
        Jwt jwt = decoder.decode(issued.accessToken());
        assertThat(jwt.getClaimAsString("system_role")).isEqualTo("system_admin");
    }

    @Test
    @DisplayName("만료된 토큰은 검증에서 거부된다")
    void expiredToken_isRejected() {
        // Given: TTL(24h) + 검증 skew(60s)보다 과거 시점에 발급
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(25)), ZoneOffset.UTC);
        JwtTokenService service = service(past);

        // When
        JwtTokenService.IssuedToken issued = service.issue(user(SystemRole.USER));

        // Then
        assertThatThrownBy(() -> decoder.decode(issued.accessToken()))
                .isInstanceOf(JwtValidationException.class);
    }

    private JwtTokenService service(Clock clock) {
        return new JwtTokenService(config.jwtEncoder(keys), properties(), clock);
    }

    private static JwtProperties properties() {
        return new JwtProperties("", TTL, ISSUER);
    }

    private static User user(SystemRole role) {
        return new User(UUID.randomUUID(), "auth@test.io", "hash", "auth-user", role);
    }
}

package io.wedocs.doc.auth;

import io.wedocs.doc.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/// JWT 발급 (ADR-0014 발급=doc-service · ADR-0017 RS256).
/// claims 최소화: sub/iss/iat/exp/system_role — email·표시이름 미포함(토큰 PII 최소화).
@RequiredArgsConstructor
@Service
public class JwtTokenService {

    /// system_role 클레임 — 값은 DB 저장 규약과 동일한 소문자(SystemRoleConverter 참조, ADR-0016).
    static final String CLAIM_SYSTEM_ROLE = "system_role";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public IssuedToken issue(User user) {
        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.ttl()))
                .claim(CLAIM_SYSTEM_ROLE, user.getSystemRole().name().toLowerCase(Locale.ROOT))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds());
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}

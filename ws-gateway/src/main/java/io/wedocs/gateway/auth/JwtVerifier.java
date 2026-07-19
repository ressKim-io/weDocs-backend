package io.wedocs.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/// RS256 JWT 검증기 (ADR-0014 검증=gateway · ADR-0017 발급=doc-service). Nimbus `JWTProcessor`로 서명(JWKS의
/// kid로 공개키 선택)·발급자·만료를 확인하고 subject(=user_id)를 돌려준다. 어떤 실패(만료·서명불일치·발급자
/// 불일치·형식오류·kid 부재)도 empty로 수렴한다 — 인증은 fail-closed(ADR-0021).
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

    private final JWTProcessor<SecurityContext> processor;

    JwtVerifier(JWTProcessor<SecurityContext> processor) {
        this.processor = processor;
    }

    /// JWKSource로부터 검증기를 조립한다 — prod(원격 JWKS URL)와 테스트(in-memory JWKSet)가 공유하는 팩토리.
    /// 필수 클레임 = subject·exp(둘 중 하나라도 없으면 거절), 발급자 일치 강제, exp는 clock skew 허용.
    public static JwtVerifier fromKeySource(JWKSource<SecurityContext> keySource, String issuer, Duration clockSkew) {
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of(JWTClaimNames.SUBJECT, JWTClaimNames.EXPIRATION_TIME));
        claimsVerifier.setMaxClockSkew((int) clockSkew.toSeconds());
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        return new JwtVerifier(processor);
    }

    /// 검증 성공 시 subject(user_id)를 반환한다. 실패는 empty(fail-closed) — 토큰 값은 로깅하지 않는다(security.md).
    public Optional<String> verifySubject(String token) {
        try {
            JWTClaimsSet claims = processor.process(token, null);
            String subject = claims.getSubject();
            return (subject != null && !subject.isBlank()) ? Optional.of(subject) : Optional.empty();
        } catch (ParseException | BadJOSEException | JOSEException e) {
            // 사유만 남기고 토큰은 남기지 않는다 — 무효/만료/위조 모두 여기로 수렴.
            log.debug("jwt verification rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

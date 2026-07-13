package io.wedocs.doc.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;

/// JWT 발급·자가 검증 배선 (ADR-0017). 검증은 메모리 공개키 직결 — 자기 자신에게 HTTP(JWKS)를 돌지 않는다.
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    @Bean
    JwtKeys jwtKeys(JwtProperties properties, ResourceLoader resourceLoader) {
        return new JwtKeys(properties.hasPrivateKeyLocation()
                ? resourceLoader.getResource(properties.privateKeyLocation())
                : null);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

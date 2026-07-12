package io.wedocs.doc.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/// RS256 서명 키 보유자 (ADR-0017) — 개인키는 doc-service만 갖는다(발급 단독 권한).
/// 검증자(gateway Phase 2 · Istio M5)는 publicJwkSet()을 JWKS 엔드포인트로 받아 공개키만 사용.
@Slf4j
public class JwtKeys {

    private static final int EPHEMERAL_KEY_BITS = 2048;

    private final RSAKey rsaKey;

    /// privatePemLocation이 null이면 임시 키 생성(dev 전용) — prod는 M5에서 Secret 주입 강제.
    public JwtKeys(Resource privatePemLocation) {
        this.rsaKey = privatePemLocation != null ? loadFromPem(privatePemLocation) : generateEphemeral();
    }

    /// 개인키 포함 — JwtEncoder(서명) 전용. 이 타입 밖으로 개인키 자료를 노출하는 유일한 경로.
    RSAKey signingKey() {
        return rsaKey;
    }

    /// JWKS 엔드포인트 응답용 — 공개키만 (개인키 자료 부재는 테스트로 고정).
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    /// 자가 검증 JwtDecoder용 공개키.
    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("jwt public key conversion failed", e);
        }
    }

    private static RSAKey loadFromPem(Resource location) {
        try (InputStream in = location.getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            // PKCS#8 개인키에서 공개키를 유도(CRT 파라미터) — 공개키 파일을 따로 배포받지 않는다.
            RSAPrivateCrtKey privateKey =
                    (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            log.info("jwt signing key loaded: location={}", location.getDescription());
            return withThumbprintKid(new RSAKey.Builder(publicKey).privateKey(privateKey).build());
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            // 잘못된 키로 조용히 기동하는 것보다 즉시 실패가 안전(fail-fast) — 원인 체인 보존(P4).
            throw new IllegalStateException("jwt private key load failed: " + location.getDescription(), e);
        }
    }

    private static RSAKey generateEphemeral() {
        // dev 전용: 재시작 시 기존 토큰 전부 무효 + 다중 replica 불가.
        // prod는 private-key-location에 K8s Secret 마운트 필수 — 강제화는 M5 매니페스트(ADR-0017 추적).
        log.warn("jwt private-key-location 미설정 — 임시 RSA 키 생성(dev 전용). "
                + "재시작 시 기존 토큰이 전부 무효화된다. prod는 반드시 키를 주입할 것(ADR-0017)");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(EPHEMERAL_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return withThumbprintKid(
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).build());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation unavailable", e);
        }
    }

    /// kid = RFC 7638 JWK thumbprint — 키 로테이션 시 JWKS에서 신·구 키를 kid로 구분(ADR-0017).
    private static RSAKey withThumbprintKid(RSAKey key) {
        try {
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("jwt kid(thumbprint) computation failed", e);
        }
    }
}

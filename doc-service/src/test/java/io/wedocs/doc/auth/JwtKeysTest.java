package io.wedocs.doc.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeysTest {

    @Test
    @DisplayName("키 위치 미설정이면 임시 키를 생성한다 — kid(RFC 7638 thumbprint) 포함")
    void ephemeral_whenNoLocation() {
        JwtKeys keys = new JwtKeys(null);

        assertThat(keys.signingKey().isPrivate()).isTrue();
        assertThat(keys.signingKey().getKeyID()).isNotBlank();
        assertThat(keys.publicKey().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("공개 JWKS에는 개인키 자료가 없다 — 공개키 1개만")
    void publicJwkSet_hasNoPrivateMaterial() {
        JwtKeys keys = new JwtKeys(null);

        var jwks = keys.publicJwkSet();
        assertThat(jwks.getKeys()).hasSize(1);
        assertThat(jwks.getKeys().getFirst().isPrivate()).isFalse();
        // 직렬화된 JSON에 개인키 지수("d")가 절대 실리지 않는다 (secure-coding P4)
        assertThat(jwks.toJSONObject().toString()).doesNotContain("\"d\"");
    }

    @Test
    @DisplayName("PKCS#8 PEM 파일에서 키를 로드한다 — 원본 키쌍과 동일 모듈러스")
    void loadsFromPem(@TempDir Path tempDir) throws Exception {
        KeyPair pair = generateRsa();
        Path pemFile = tempDir.resolve("jwt-private.pem");
        Files.writeString(pemFile, toPkcs8Pem(pair));

        JwtKeys keys = new JwtKeys(new FileSystemResource(pemFile));

        RSAPublicKey original = (RSAPublicKey) pair.getPublic();
        assertThat(keys.publicKey().getModulus()).isEqualTo(original.getModulus());
        assertThat(keys.signingKey().getKeyID()).isNotBlank();
    }

    @Test
    @DisplayName("PEM이 깨져 있으면 기동 시점에 즉시 실패한다 — 조용한 폴백 금지")
    void failsFast_onInvalidPem(@TempDir Path tempDir) throws Exception {
        Path pemFile = tempDir.resolve("broken.pem");
        Files.writeString(pemFile, "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> new JwtKeys(new FileSystemResource(pemFile)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static KeyPair generateRsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String toPkcs8Pem(KeyPair pair) {
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }
}

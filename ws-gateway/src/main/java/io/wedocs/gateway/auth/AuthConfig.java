package io.wedocs.gateway.auth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/// 인증 배선 (ADR-0014/0017/0021). 원격 JWKS(doc-service)에서 공개키를 받아 검증기를 만든다.
/// `JWKSourceBuilder`는 5분 캐시 + 만료 30초 전 백그라운드 갱신 + 미지의 kid 도착 시 rate-limited 재조회를
/// 내장한다(Nimbus 10.9 기본값, WebFetch 검증 2026-07-19) → 키 로테이션 무중단(Q3). fetch는 지연 실행이라
/// (첫 검증 시) 컨텍스트 기동은 doc-service 없이도 성립. 실제 URL fetch는 `MeteredResourceRetriever`로 감싸
/// jwks_refresh_total을 계측한다(ADR-0021) — 캐시 히트는 retriever를 안 타므로 fetch만 정확히 센다.
@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class AuthConfig {

    @Bean
    public JwtVerifier jwtVerifier(GatewayAuthProperties properties, AuthMetrics metrics) {
        return JwtVerifier.fromKeySource(
                remoteKeySource(properties.jwksUri(), metrics), properties.issuer(), properties.clockSkew());
    }

    private static JWKSource<SecurityContext> remoteKeySource(String jwksUri, AuthMetrics metrics) {
        try {
            // URI→URL: new URL(String)은 JDK20+ deprecated — URI 경유로 파싱한다.
            URL url = URI.create(jwksUri).toURL();
            ResourceRetriever retriever = new MeteredResourceRetriever(defaultRetriever(), metrics);
            return JWKSourceBuilder.<SecurityContext>create(url, retriever).retrying(true).build();
        } catch (MalformedURLException | IllegalArgumentException e) {
            // 잘못된 JWKS URI로 조용히 기동하면 전 연결이 런타임에 인증 실패 → 기동 시 즉시 실패(fail-fast).
            throw new IllegalStateException("invalid wedocs.gateway.auth.jwks-uri: " + jwksUri, e);
        }
    }

    /// Nimbus 기본값과 동일한 타임아웃·크기 상한으로 기본 retriever 구성 — 데코레이션이 기본 HTTP 동작을
    /// 바꾸지 않도록 상수를 명시 재사용한다(config-contract-audit: 암묵 기본값 의존 대신 명시).
    private static ResourceRetriever defaultRetriever() {
        return new DefaultResourceRetriever(
                JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT,
                JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT,
                JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT);
    }

    /// JWKS URL fetch 성공/실패를 계측하는 데코레이터. 캐싱·rate-limit·retry 레이어 아래 실제 네트워크 fetch만
    /// 통과하므로, 여기서 세면 doc-service JWKS 도달성(=검증키 소스 건강)을 정확히 관측한다.
    static final class MeteredResourceRetriever implements ResourceRetriever {

        private final ResourceRetriever delegate;
        private final AuthMetrics metrics;

        MeteredResourceRetriever(ResourceRetriever delegate, AuthMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            try {
                Resource resource = delegate.retrieveResource(url);
                metrics.jwksRefresh(AuthMetrics.RESULT_OK);
                return resource;
            } catch (IOException e) {
                metrics.jwksRefresh(AuthMetrics.RESULT_FAIL);
                throw e;
            }
        }
    }
}

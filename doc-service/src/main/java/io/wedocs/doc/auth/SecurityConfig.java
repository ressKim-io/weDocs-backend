package io.wedocs.doc.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/// REST 보안 배선 (ADR-0014 REST 검증=doc-service self · ADR-0017 RS256).
/// 기본 거부 + 명시 공개(인증·JWKS)만 예외 (secure-coding P3).
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // stateless Bearer 전용 — 세션·쿠키를 만들지 않아 CSRF 공격면이 없다(P5 완화 근거).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/.well-known/jwks.json").permitAll()
                        .anyRequest().authenticated())
                // JwtDecoder 빈(JwtConfig, 메모리 공개키) 사용 — Bearer 토큰 자가 검증.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    /// bcrypt 기본 + `{bcrypt}` 프리픽스(varchar(255) 내) — 알고리즘 마이그레이션 대비 위임 인코더.
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            // 환경별 override 가능(env WEDOCS_CORS_ALLOWED_ORIGINS) — jwt.private-key-location과 동일 패턴.
            @Value("${wedocs.doc-service.cors-allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // 화이트리스트만 — `*` 금지(P5). 기본 = vite dev 서버(5173), prod 도메인은 M5 배포 값으로 주입.
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

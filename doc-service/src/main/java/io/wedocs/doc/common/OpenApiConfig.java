package io.wedocs.doc.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// OpenAPI 3.1 메타 + Bearer JWT 스킴 — Swagger UI에서 Authorize 버튼으로 토큰 주입 가능.
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("weDocs doc-service API")
                        .version("0.1.0")
                        .description("문서 협업 플랫폼 REST API — 인증, 워크스페이스, 페이지 트리, 공유"))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("POST /api/auth/login 응답의 accessToken")));
    }
}

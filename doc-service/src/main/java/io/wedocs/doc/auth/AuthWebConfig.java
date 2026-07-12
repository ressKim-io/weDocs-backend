package io.wedocs.doc.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/// @CurrentUserId 리졸버 등록. 순수 무상태 리졸버라 직접 생성 — 스프링 빈 협력자 아님(design-patterns P2 예외).
@Configuration
class AuthWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}

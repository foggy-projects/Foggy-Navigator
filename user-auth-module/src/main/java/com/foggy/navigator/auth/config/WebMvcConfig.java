package com.foggy.navigator.auth.config;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import com.foggy.navigator.auth.interceptor.AuthorizationShadowInterceptor;
import com.foggy.navigator.auth.interceptor.TypedManagementAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuthorizationShadowInterceptor authorizationShadowInterceptor;
    private final TypedManagementAuthInterceptor typedManagementAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/health/**",
                        "/api/v1/management/v1/**"
                );
        registry.addInterceptor(typedManagementAuthInterceptor)
                .addPathPatterns("/api/v1/management/v1/**");
        registry.addInterceptor(authorizationShadowInterceptor)
                .addPathPatterns("/api/**", "/internal/worker-gateway/v1/**", "/diagnostic-share/**")
                .excludePathPatterns(
                        "/api/v1/management/v1/**",
                        "/api/v1/open/runtime/binding-audit",
                        "/api/v1/open/runtime/task-audit"
                );
    }

    /**
     * A bean-level mapped interceptor is discovered by framework-owned handler
     * mappings too, including Actuator and the SSH WebSocket handshake mapping.
     */
    @Bean
    public MappedInterceptor authorizationShadowFrameworkIngressInterceptor() {
        return new MappedInterceptor(
                new String[]{"/actuator/**", "/api/v1/ssh/*/ws"},
                authorizationShadowInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .exposedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}

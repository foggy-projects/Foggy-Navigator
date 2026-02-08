package com.foggy.navigator.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置
 * <p>
 * 配置 JWT 认证模式：
 * - 禁用 CSRF（REST API 不需要）
 * - 禁用 Session（无状态认证）
 * - 配置 CORS
 * - 开放登录/注册接口
 */
@Slf4j
@Configuration
@EnableWebSecurity
@Order(1)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("=== Configuring Spring Security with JWT mode (Stateless) ===");

        http
                // 禁用 CSRF（REST API 使用 JWT，不需要 CSRF 保护）
                .csrf(AbstractHttpConfigurer::disable)

                // 配置 CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 禁用 Session（使用 JWT 无状态认证）
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 禁用默认的表单登录
                .formLogin(AbstractHttpConfigurer::disable)

                // 禁用 HTTP Basic 认证
                .httpBasic(AbstractHttpConfigurer::disable)

                // 配置请求授权
                .authorizeHttpRequests(auth -> auth
                        // 开放登录和注册接口
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        // 开放健康检查接口
                        .requestMatchers("/api/v1/health/**", "/actuator/**").permitAll()
                        // 开放静态资源（前端构建产物）
                        .requestMatchers("/", "/index.html", "/*.html", "/assets/**", "/*.js", "/*.css", "/*.ico", "/*.png", "/*.jpg", "/*.svg").permitAll()
                        // 开放前端资源路径（包括所有子路径）
                        .requestMatchers("/js/**", "/css/**", "/img/**", "/fonts/**", "/static/**").permitAll()
                        // 开放统计数据 API（用于首页显示，无需认证）
                        .requestMatchers("/api/v1/sessions/stats", "/api/v1/sessions/count", "/api/v1/containers/stats").permitAll()
                        .requestMatchers("/api/v1/events/today", "/api/v1/messages/today").permitAll()
                        // 临时开放：允许匿名访问列表 API（仅用于开发测试，生产环境应移除）
                        .requestMatchers("/api/v1/environments", "/api/v1/environments/**").permitAll()
                        .requestMatchers("/api/v1/conversations", "/api/v1/conversations/**").permitAll()
                        .requestMatchers("/api/v1/containers", "/api/v1/containers/**").permitAll()
                        .requestMatchers("/api/v1/events", "/api/v1/events/**").permitAll()
                        .requestMatchers("/api/v1/messages", "/api/v1/messages/**").permitAll()
                        .requestMatchers("/api/v1/git-credentials", "/api/v1/git-credentials/**").permitAll()
                        .requestMatchers("/api/v1/git", "/api/v1/git/**").permitAll()
                        .requestMatchers("/api/v1/sessions", "/api/v1/sessions/**").permitAll()
                        .requestMatchers("/api/v1/config", "/api/v1/config/**").permitAll()
                        // 开放 Spring Boot 错误端点（避免异常转发时被拦截返回 403）
                        .requestMatchers("/error").permitAll()
                        // 其他所有请求都需要认证（但具体权限检查由 @RequireAuth AOP 处理）
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    /**
     * CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 允许的源（开发环境允许所有，生产环境应该配置具体域名）
        configuration.setAllowedOriginPatterns(List.of("*"));

        // 允许的 HTTP 方法
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // 允许的请求头
        configuration.setAllowedHeaders(List.of("*"));

        // 允许携带认证信息
        configuration.setAllowCredentials(true);

        // 暴露的响应头
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));

        // 预检请求的有效期（秒）
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * 密码加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

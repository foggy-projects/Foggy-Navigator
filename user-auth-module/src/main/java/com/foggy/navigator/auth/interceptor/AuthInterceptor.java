package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.auth.util.JwtUtil;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.spi.auth.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 认证拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserAuthService userAuthService;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 尝试从请求中获取用户信息
        CurrentUser currentUser = extractUser(request, response);

        if (currentUser != null) {
            UserContext.setCurrentUser(currentUser);
            request.setAttribute("userId", currentUser.getUserId());
            request.setAttribute("username", currentUser.getUsername());
            request.setAttribute("tenantId", currentUser.getTenantId());
            request.setAttribute("roles", currentUser.getRoles());
            log.debug("User authenticated: userId={}, username={}",
                    currentUser.getUserId(), currentUser.getUsername());
        }

        // 注意：这里不拦截请求，只是设置上下文
        // 具体的权限检查由各 Controller 或 AOP 处理
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清理用户上下文，防止内存泄漏
        UserContext.clear();
    }

    private static final String RENEW_TOKEN_HEADER = "X-New-Token";

    /**
     * 从请求中提取用户信息，并在 token 剩余有效期不足一半时自动续期
     */
    private CurrentUser extractUser(HttpServletRequest request, HttpServletResponse response) {
        // 1. 尝试从 JWT Token 获取
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            CurrentUser user = extractFromToken(token);
            if (user != null && jwtUtil.needsRenewal(token)) {
                String newToken = jwtUtil.renewToken(token);
                response.setHeader(RENEW_TOKEN_HEADER, newToken);
                log.debug("Token renewed for user: {}", user.getUsername());
            }
            return user;
        }

        // 2. 尝试从 URL query param 获取 token（SSE 场景，EventSource 不支持自定义 header）
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isEmpty()) {
            return extractFromToken(queryToken);
        }

        // 3. 尝试从 API Key 获取
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isEmpty()) {
            return extractFromApiKey(apiKey);
        }

        return null;
    }

    /**
     * 从 JWT Token 提取用户信息
     */
    private CurrentUser extractFromToken(String token) {
        try {
            if (!jwtUtil.validateToken(token)) {
                return null;
            }

            return CurrentUser.builder()
                    .userId(jwtUtil.getUserIdFromToken(token))
                    .username(jwtUtil.getUsernameFromToken(token))
                    .tenantId(jwtUtil.getTenantIdFromToken(token))
                    .roles(jwtUtil.getRolesFromToken(token))
                    .build();
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 API Key 提取用户信息
     */
    private CurrentUser extractFromApiKey(String apiKey) {
        try {
            Optional<UserDTO> userOpt = userAuthService.getUserByApiKey(apiKey);
            if (userOpt.isEmpty()) {
                return null;
            }

            UserDTO user = userOpt.get();
            return CurrentUser.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .tenantId(user.getTenantId())
                    .roles(user.getRoles())
                    .build();
        } catch (Exception e) {
            log.warn("Invalid API key: {}", e.getMessage());
            return null;
        }
    }
}

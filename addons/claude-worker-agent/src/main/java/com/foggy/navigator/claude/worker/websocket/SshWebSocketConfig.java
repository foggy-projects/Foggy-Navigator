package com.foggy.navigator.claude.worker.websocket;

import com.foggy.navigator.auth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * SSH WebSocket 代理配置 — 注册 handler 并进行 JWT 校验。
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class SshWebSocketConfig implements WebSocketConfigurer {

    private final SshWebSocketProxyHandler sshWebSocketProxyHandler;
    private final JwtUtil jwtUtil;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sshWebSocketProxyHandler, "/api/v1/ssh/*/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor());
    }

    private HandshakeInterceptor jwtHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();

                // JWT 校验
                String token = params.getFirst("token");
                if (token == null || token.isEmpty()) {
                    log.warn("SSH WS handshake rejected: missing token");
                    return false;
                }
                if (!jwtUtil.validateToken(token)) {
                    log.warn("SSH WS handshake rejected: invalid token");
                    return false;
                }

                // 提取 workerId
                String workerId = params.getFirst("workerId");
                if (workerId == null || workerId.isEmpty()) {
                    log.warn("SSH WS handshake rejected: missing workerId");
                    return false;
                }

                attributes.put("workerId", workerId);
                attributes.put("userId", jwtUtil.getUserIdFromToken(token));
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
                // no-op
            }
        };
    }
}

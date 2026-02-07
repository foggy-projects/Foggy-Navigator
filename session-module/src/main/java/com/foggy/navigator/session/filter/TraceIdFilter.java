package com.foggy.navigator.session.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceId 过滤器
 * 为每个 HTTP 请求生成 traceId，放入 MDC 并添加到响应头
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = generateTraceId();
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

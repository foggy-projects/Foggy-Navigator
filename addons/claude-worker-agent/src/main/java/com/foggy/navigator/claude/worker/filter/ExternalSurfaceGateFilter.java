package com.foggy.navigator.claude.worker.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.config.ExternalSurfaceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Fail-closed gate for the public Open API surface.
 *
 * <p>This is deliberately independent from the global security configuration:
 * when explicitly enabled, existing Open API authentication and authorization
 * continue to run unchanged.</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ExternalSurfaceGateFilter extends OncePerRequestFilter {

    public static final String DISABLED_CODE = "EXTERNAL_SURFACE_DISABLED";
    private static final String OPEN_API_ROOT = "/api/v1/open";

    private final ExternalSurfaceProperties properties;
    private final ObjectMapper objectMapper;

    public ExternalSurfaceGateFilter(ExternalSurfaceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return properties.isEnabled() || !isOpenApiPath(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", DISABLED_CODE,
                "message", "External API surface is disabled"
        ));
    }

    static boolean isOpenApiPath(String path) {
        return OPEN_API_ROOT.equals(path) || path.startsWith(OPEN_API_ROOT + "/");
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
    }
}

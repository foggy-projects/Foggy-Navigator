package com.foggy.navigator.auth.authorization;

import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the non-controller launcher ingress families which must retain their
 * source-controlled manifest identities during P1A shadow observation.
 */
@Component
public class AuthorizationIngressRouteResolver {

    private static final String ACTUATOR_ROOT_ROUTE_ID = "framework:get:/actuator";
    private static final String ACTUATOR_BEANS_ROUTE_ID = "framework:get:/actuator/beans";
    private static final String ACTUATOR_HEALTH_ROUTE_ID = "framework:get:/actuator/health{/**}";
    private static final String ACTUATOR_INFO_ROUTE_ID = "framework:get:/actuator/info";
    private static final String ACTUATOR_METRICS_ROUTE_ID = "framework:get:/actuator/metrics{/**}";
    private static final String SSH_WEBSOCKET_ROUTE_ID = "websocket:connect:/api/v1/ssh/{sessionId}/ws";

    private static final String ACTUATOR_ROOT_PATH = "/actuator";
    private static final String ACTUATOR_BEANS_PATH = "/actuator/beans";
    private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";
    private static final String ACTUATOR_INFO_PATH = "/actuator/info";
    private static final String ACTUATOR_METRICS_PATH = "/actuator/metrics";
    private static final String SSH_WEBSOCKET_PREFIX = "/api/v1/ssh/";
    private static final String SSH_WEBSOCKET_SUFFIX = "/ws";

    private final AuthorizationRouteCatalog routeCatalog;

    public AuthorizationIngressRouteResolver(AuthorizationRouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    /**
     * Resolves only the six approved launcher ingress entries. The incoming
     * WebSocket upgrade is HTTP GET but intentionally resolves to the
     * manifest's WEBSOCKET metadata rather than a derived MVC route id.
     */
    public Optional<AuthorizationRouteManifestEntry> resolve(HttpServletRequest request) {
        if (request == null || !"GET".equals(request.getMethod())) {
            return Optional.empty();
        }

        String routeId = routeIdForPath(requestPath(request));
        return routeId == null ? Optional.empty() : routeCatalog.findByRouteId(routeId);
    }

    private static String routeIdForPath(String path) {
        if (ACTUATOR_ROOT_PATH.equals(path)) {
            return ACTUATOR_ROOT_ROUTE_ID;
        }
        if (ACTUATOR_BEANS_PATH.equals(path)) {
            return ACTUATOR_BEANS_ROUTE_ID;
        }
        if (ACTUATOR_INFO_PATH.equals(path)) {
            return ACTUATOR_INFO_ROUTE_ID;
        }
        if (isFamilyPath(path, ACTUATOR_HEALTH_PATH)) {
            return ACTUATOR_HEALTH_ROUTE_ID;
        }
        if (isFamilyPath(path, ACTUATOR_METRICS_PATH)) {
            return ACTUATOR_METRICS_ROUTE_ID;
        }
        if (isSshWebSocketPath(path)) {
            return SSH_WEBSOCKET_ROUTE_ID;
        }
        return null;
    }

    private static boolean isFamilyPath(String path, String familyRoot) {
        return familyRoot.equals(path) || (path != null && path.startsWith(familyRoot + "/"));
    }

    private static boolean isSshWebSocketPath(String path) {
        if (path == null || !path.startsWith(SSH_WEBSOCKET_PREFIX) || !path.endsWith(SSH_WEBSOCKET_SUFFIX)) {
            return false;
        }
        int sessionStart = SSH_WEBSOCKET_PREFIX.length();
        int sessionEnd = path.length() - SSH_WEBSOCKET_SUFFIX.length();
        if (sessionEnd <= sessionStart) {
            return false;
        }
        String sessionId = path.substring(sessionStart, sessionEnd);
        return !sessionId.isBlank() && sessionId.indexOf('/') < 0;
    }

    private static String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}

package com.foggy.navigator.auth.authorization;

import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationIngressRouteResolverTest {

    private final AuthorizationIngressRouteResolver resolver =
            new AuthorizationIngressRouteResolver(new AuthorizationRouteCatalog());

    @Test
    void resolvesAllSixApprovedFrameworkAndWebSocketEntries() {
        List<ResolvedIngress> ingress = List.of(
                new ResolvedIngress("/actuator", "framework:get:/actuator"),
                new ResolvedIngress("/actuator/beans", "framework:get:/actuator/beans"),
                new ResolvedIngress("/actuator/info", "framework:get:/actuator/info"),
                new ResolvedIngress("/actuator/health/liveness", "framework:get:/actuator/health{/**}"),
                new ResolvedIngress("/actuator/metrics/jvm.memory.used", "framework:get:/actuator/metrics{/**}"),
                new ResolvedIngress("/api/v1/ssh/session-42/ws", "websocket:connect:/api/v1/ssh/{sessionId}/ws")
        );

        Set<String> resolvedRouteIds = new LinkedHashSet<>();
        for (ResolvedIngress expected : ingress) {
            AuthorizationRouteManifestEntry entry = resolver.resolve(get(expected.path())).orElseThrow();
            resolvedRouteIds.add(entry.routeId());
            assertEquals(expected.routeId(), entry.routeId());
        }

        assertEquals(6, resolvedRouteIds.size(), "all approved non-MVC launcher ingress entries must resolve");
        assertEquals(Set.of(
                "framework:get:/actuator",
                "framework:get:/actuator/beans",
                "framework:get:/actuator/health{/**}",
                "framework:get:/actuator/info",
                "framework:get:/actuator/metrics{/**}",
                "websocket:connect:/api/v1/ssh/{sessionId}/ws"
        ), resolvedRouteIds);
    }

    @Test
    void normalizesActuatorFamiliesAndContextPathWithoutUsingRequestValuesAsMetadata() {
        AuthorizationRouteManifestEntry health = resolver.resolve(get("/actuator/health")).orElseThrow();
        assertEquals("framework:get:/actuator/health{/**}", health.routeId());

        MockHttpServletRequest metricsWithContext = get("/navigator/actuator/metrics/jvm.threads.live");
        metricsWithContext.setContextPath("/navigator");
        AuthorizationRouteManifestEntry metrics = resolver.resolve(metricsWithContext).orElseThrow();
        assertEquals("framework:get:/actuator/metrics{/**}", metrics.routeId());
        assertEquals("/actuator/metrics{/**}", metrics.path());

        AuthorizationRouteManifestEntry ssh = resolver.resolve(get("/api/v1/ssh/session-42/ws")).orElseThrow();
        assertEquals("WEBSOCKET", ssh.httpMethod());
        assertEquals("/api/v1/ssh/{sessionId}/ws", ssh.path());
    }

    @Test
    void rejectsWrongMethodsAndUnregisteredPaths() {
        assertTrue(resolver.resolve(request("POST", "/actuator")).isEmpty());
        assertTrue(resolver.resolve(request("POST", "/actuator/beans")).isEmpty());
        assertTrue(resolver.resolve(request("POST", "/actuator/health/liveness")).isEmpty());
        assertTrue(resolver.resolve(request("POST", "/api/v1/ssh/session-42/ws")).isEmpty());
        assertTrue(resolver.resolve(get("/actuator/healthcheck")).isEmpty());
        assertTrue(resolver.resolve(get("/api/v1/ssh/session-42/ws/extra")).isEmpty());
        assertTrue(resolver.resolve(get("/api/v1/ssh//ws")).isEmpty());
    }

    private static MockHttpServletRequest get(String path) {
        return request("GET", path);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private record ResolvedIngress(String path, String routeId) {
    }
}

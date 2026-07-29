package com.foggy.navigator.launcher;

import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.claude.worker.websocket.SshWebSocketConfig;
import com.foggy.navigator.claude.worker.websocket.SshWebSocketProxyHandler;
import com.foggy.navigator.auth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Guards the approved P0.5 launcher ingress inventory. The test asks Spring MVC
 * to resolve controller annotations rather than matching source text, so a new
 * MVC handler cannot silently bypass the source-controlled authorization catalog.
 */
class AuthorizationRouteManifestCoverageTest {

    private static final String NAVIGATOR_BASE_PACKAGE = "com.foggy.navigator";
    private static final int EXPECTED_LAUNCHER_MVC_ROUTE_COUNT = 445;

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();

    @Test
    void everyLauncherMvcIngressIsRegisteredInTheFrozenDeploymentAwareCatalog() throws Exception {
        Set<MvcIngress> discovered = discoverLauncherMvcIngresses();
        Set<MvcIngress> registered = catalog.entriesByRouteId().values().stream()
                .filter(entry -> AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER.equals(entry.deployment()))
                .filter(entry -> entry.routeId().startsWith("mvc:"))
                .map(entry -> MvcIngress.of(entry.httpMethod(), entry.path()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertEquals(EXPECTED_LAUNCHER_MVC_ROUTE_COUNT, registered.size(),
                "The approved P0.5 catalog must retain all launcher MVC rows");
        assertEquals(registered, discovered,
                () -> "Every launcher MVC ingress must have a deployment-aware catalog row. "
                        + "Missing registrations=" + difference(discovered, registered)
                        + "; stale catalog rows=" + difference(registered, discovered));
    }

    @Test
    void observerEntriesRemainSeparateEvenWhenTheMethodAndPathMatchLauncher() {
        AuthorizationRouteManifestEntry launcher = catalog.findByDeploymentMethodAndPath(
                        AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                        "POST",
                        "/api/v1/open/agents/{agentId}/ask")
                .orElseThrow();
        AuthorizationRouteManifestEntry observer = catalog.findByDeploymentMethodAndPath(
                        AuthorizationRouteCatalog.DEPLOYMENT_OBSERVER_BFF,
                        "POST",
                        "/api/v1/open/agents/{agentId}/ask")
                .orElseThrow();

        assertFalse(launcher.routeId().equals(observer.routeId()));
        assertEquals("mvc:post:/api/v1/open/agents/{agentId}/ask", launcher.routeId());
        assertEquals("mvc:observer-bff:post:/api/v1/open/agents/{agentId}/ask", observer.routeId());
    }

    @Test
    void nonMvcActuatorAndSshIngressRemainInTheFrozenCatalogAndConcreteRegistrations() {
        Set<String> boundaryRouteIds = catalog.entriesByRouteId().values().stream()
                .filter(entry -> AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER.equals(entry.deployment()))
                .filter(entry -> entry.routeId().startsWith("framework:")
                        || entry.routeId().startsWith("websocket:"))
                .map(AuthorizationRouteManifestEntry::routeId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "framework:get:/actuator",
                "framework:get:/actuator/beans",
                "framework:get:/actuator/health{/**}",
                "framework:get:/actuator/info",
                "framework:get:/actuator/metrics{/**}",
                "websocket:connect:/api/v1/ssh/{sessionId}/ws"), boundaryRouteIds);

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("false", properties.getProperty("management.endpoints.web.discovery.enabled"),
                "Owner-approved disabled discovery links must not be re-enabled without a manifest amendment");
        assertEquals("${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health,beans,metrics,info}",
                properties.getProperty("management.endpoints.web.exposure.include"),
                "A newly exposed actuator endpoint needs a manifest review before it can be enabled");

        CapturingWebSocketHandlerRegistry registry = new CapturingWebSocketHandlerRegistry();
        SshWebSocketProxyHandler handler = mock(SshWebSocketProxyHandler.class);
        new SshWebSocketConfig(handler, mock(JwtUtil.class)).registerWebSocketHandlers(registry);
        assertEquals(handler, registry.handler);
        assertArrayEquals(new String[]{"/api/v1/ssh/*/ws"}, registry.paths,
                "A changed SSH WebSocket ingress needs a matching manifest review");
    }

    private static Set<MvcIngress> discoverLauncherMvcIngresses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class, true));

        SpringMappingProbe mappingProbe = new SpringMappingProbe();
        Set<MvcIngress> discovered = new LinkedHashSet<>();
        ClassLoader classLoader = AuthorizationRouteManifestCoverageTest.class.getClassLoader();
        for (BeanDefinition candidate : scanner.findCandidateComponents(NAVIGATOR_BASE_PACKAGE)) {
            Class<?> controllerType = ClassUtils.forName(candidate.getBeanClassName(), classLoader);
            Map<Method, RequestMappingInfo> mappings = MethodIntrospector.selectMethods(controllerType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method ->
                            mappingProbe.mappingFor(method, controllerType));
            for (RequestMappingInfo mapping : mappings.values()) {
                Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
                assertFalse(methods.isEmpty(), () -> "Every launcher MVC ingress must declare an HTTP method: "
                        + controllerType.getName() + " " + mapping);
                for (RequestMethod method : methods) {
                    for (PathPattern pattern : mapping.getPathPatternsCondition().getPatterns()) {
                        discovered.add(MvcIngress.of(method.name(), pattern.getPatternString()));
                    }
                }
            }
        }
        return discovered;
    }

    private static Set<MvcIngress> difference(Set<MvcIngress> left, Set<MvcIngress> right) {
        Set<MvcIngress> difference = new TreeSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private record MvcIngress(String method, String path) implements Comparable<MvcIngress> {

        private static MvcIngress of(String method, String path) {
            return new MvcIngress(method, path);
        }

        @Override
        public int compareTo(MvcIngress other) {
            int methodComparison = method.compareTo(other.method);
            return methodComparison != 0 ? methodComparison : path.compareTo(other.path);
        }
    }

    private static final class SpringMappingProbe extends RequestMappingHandlerMapping {

        private RequestMappingInfo mappingFor(Method method, Class<?> controllerType) {
            return getMappingForMethod(method, controllerType);
        }
    }

    private static final class CapturingWebSocketHandlerRegistry implements WebSocketHandlerRegistry {

        private final WebSocketHandlerRegistration registration = new NoopWebSocketHandlerRegistration();
        private WebSocketHandler handler;
        private String[] paths;

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler webSocketHandler, String... urls) {
            this.handler = webSocketHandler;
            this.paths = urls;
            return registration;
        }
    }

    private static final class NoopWebSocketHandlerRegistration implements WebSocketHandlerRegistration {

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler handler, String... paths) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setHandshakeHandler(HandshakeHandler handshakeHandler) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration addInterceptors(HandshakeInterceptor... interceptors) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOrigins(String... allowedOrigins) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOriginPatterns(String... allowedOriginPatterns) {
            return this;
        }

        @Override
        public SockJsServiceRegistration withSockJS() {
            return null;
        }
    }
}

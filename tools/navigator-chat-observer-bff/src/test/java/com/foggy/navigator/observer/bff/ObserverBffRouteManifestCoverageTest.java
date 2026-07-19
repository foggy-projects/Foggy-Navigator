package com.foggy.navigator.observer.bff;

import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Guards the independently deployed Observer BFF ingress inventory. */
class ObserverBffRouteManifestCoverageTest {

    private static final String OBSERVER_BFF_BASE_PACKAGE = "com.foggy.navigator.observer.bff";
    private static final int EXPECTED_OBSERVER_BFF_MVC_ROUTE_COUNT = 12;

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();

    @Test
    void everyObserverBffMvcIngressIsRegisteredSeparatelyFromLauncher() throws Exception {
        Set<MvcIngress> discovered = discoverObserverBffMvcIngresses();
        Set<MvcIngress> registered = catalog.entriesByRouteId().values().stream()
                .filter(entry -> AuthorizationRouteCatalog.DEPLOYMENT_OBSERVER_BFF.equals(entry.deployment()))
                .filter(entry -> entry.routeId().startsWith("mvc:observer-bff:"))
                .map(entry -> MvcIngress.of(entry.httpMethod(), entry.path()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertEquals(EXPECTED_OBSERVER_BFF_MVC_ROUTE_COUNT, registered.size(),
                "The approved P0.5 catalog must retain all Observer BFF MVC rows");
        assertEquals(registered, discovered,
                () -> "Every Observer BFF ingress must retain its own deployment-aware catalog row. "
                        + "Missing registrations=" + difference(discovered, registered)
                        + "; stale catalog rows=" + difference(registered, discovered));
    }

    private static Set<MvcIngress> discoverObserverBffMvcIngresses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class, true));

        SpringMappingProbe mappingProbe = new SpringMappingProbe();
        Set<MvcIngress> discovered = new LinkedHashSet<>();
        ClassLoader classLoader = ObserverBffRouteManifestCoverageTest.class.getClassLoader();
        for (BeanDefinition candidate : scanner.findCandidateComponents(OBSERVER_BFF_BASE_PACKAGE)) {
            Class<?> controllerType = ClassUtils.forName(candidate.getBeanClassName(), classLoader);
            Map<Method, RequestMappingInfo> mappings = MethodIntrospector.selectMethods(controllerType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method ->
                            mappingProbe.mappingFor(method, controllerType));
            for (RequestMappingInfo mapping : mappings.values()) {
                Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
                assertFalse(methods.isEmpty(), () -> "Every Observer BFF ingress must declare an HTTP method: "
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
}

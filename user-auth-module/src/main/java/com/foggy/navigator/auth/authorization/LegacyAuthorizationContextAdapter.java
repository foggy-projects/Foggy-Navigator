package com.foggy.navigator.auth.authorization;

import com.foggy.navigator.common.authorization.AuthorizationContextV1;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationRequiredSection;
import com.foggy.navigator.common.authorization.AuthorizationResolutionState;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.AuthorizationSchemaV1;
import com.foggy.navigator.common.authorization.DeploymentIdentity;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentityResolver;
import com.foggy.navigator.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds an intentionally sparse canonical context from observable legacy
 * request shape. It only observes header and query-parameter names; raw
 * credential material, account data, request bodies, and legacy validation
 * services are deliberately outside this P1A shadow adapter.
 */
@Component
@RequiredArgsConstructor
public class LegacyAuthorizationContextAdapter {

    public static final String CORRELATION_ID_ATTRIBUTE =
            LegacyAuthorizationContextAdapter.class.getName() + ".correlationId";
    public static final String DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE =
            LegacyAuthorizationContextAdapter.class.getName() + ".deploymentIdentityOverrideAttempt";

    private static final String DEPLOYMENT = AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER;
    private static final String HEADER_UPSTREAM_ADMIN = "x-navi-admin-key";
    private static final String HEADER_CLIENT_APP_CONTROL = "x-client-app-control-key";
    private static final String HEADER_CLIENT_APP_KEY = "x-client-app-key";
    private static final String HEADER_CLIENT_APP_SECRET = "x-client-app-secret";
    private static final String HEADER_CLIENT_APP_ACCESS_TOKEN = "x-client-app-access-token";
    private static final String HEADER_TASK_SCOPED_TOKEN = "x-task-scoped-token";
    private static final String HEADER_WORKER_ID = "x-navigator-worker-id";
    private static final String HEADER_WORKER_CREDENTIAL = "x-navigator-worker-credential";
    private static final String HEADER_WORKER_LEASE_ID = "x-navigator-worker-lease-id";
    private static final String HEADER_AUTHORIZATION = "authorization";
    private static final String HEADER_API_KEY = "x-api-key";
    private static final String QUERY_AUTH_TOKEN = "token";

    private final DeploymentIdentityProvider deploymentIdentityProvider;
    private final AuthorizationRouteCatalog routeCatalog;

    public AuthorizationContextV1 adapt(HttpServletRequest request) {
        return adapt(request, route(request));
    }

    /**
     * Adapts an ingress which has already been resolved to a current
     * source-controlled manifest entry. Request method and path are never
     * allowed to replace that entry's route, action, or deployment metadata.
     */
    public AuthorizationContextV1 adapt(HttpServletRequest request,
                                        AuthorizationRouteManifestEntry resolvedRoute) {
        return adapt(request, route(resolvedRoute));
    }

    private AuthorizationContextV1 adapt(HttpServletRequest request, RouteObservation route) {
        RequestNames requestNames = requestNames(request);
        CredentialObservation credential = credentialObservation(requestNames);
        DeploymentIdentity deploymentIdentity = deploymentIdentityProvider.deploymentIdentity();
        AuthorizationRouteManifestEntry entry = route.entry();

        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                AuthorizationSchemaV1.UNKNOWN_SERVER_BUILD,
                correlationId(request),
                new AuthorizationContextV1.Deployment(
                        deploymentIdentity.navigatorInstanceId(),
                        deploymentIdentity.environmentProfile(),
                        deploymentIdentity.source().name(),
                        deploymentIdentity.productionUsable()),
                new AuthorizationContextV1.Principal(
                        credential.principalType(),
                        credential.principalReference(),
                        credential.assurance(),
                        credential.resolutionState()),
                new AuthorizationContextV1.Credential(
                        credential.credentialLane(),
                        credential.credentialReference(),
                        credential.resolutionState()),
                new AuthorizationContextV1.Action(route.actionId()),
                new AuthorizationContextV1.Route(route.routeId(), route.deployment(),
                        route.httpMethod(), route.path()),
                new AuthorizationContextV1.Trust("legacy-unverified",
                        AuthorizationResolutionState.UNVERIFIED),
                targetObservation(entry),
                null,
                null,
                credential.sourceConflict(),
                requestNames.identityOverrideAttempt(),
                null,
                null,
                null,
                null);
    }

    private static AuthorizationContextV1.Target targetObservation(AuthorizationRouteManifestEntry entry) {
        if (entry == null || !entry.requires(AuthorizationRequiredSection.TARGET)) {
            return null;
        }
        return new AuthorizationContextV1.Target(entry.targetResolver(), AuthorizationResolutionState.UNVERIFIED);
    }

    private RouteObservation route(HttpServletRequest request) {
        String httpMethod = textOrDefault(request == null ? null : request.getMethod(), "UNKNOWN");
        String path = bestMatchingPath(request);
        String routeId = AuthorizationRouteCatalog.routeId(DEPLOYMENT, httpMethod, path);
        AuthorizationRouteManifestEntry entry = routeCatalog.findByRouteId(routeId).orElse(null);
        return new RouteObservation(routeId, DEPLOYMENT, httpMethod, path,
                entry == null ? "unregistered.action" : entry.canonicalAction(), entry);
    }

    private RouteObservation route(AuthorizationRouteManifestEntry resolvedRoute) {
        Objects.requireNonNull(resolvedRoute, "resolvedRoute must not be null");
        AuthorizationRouteManifestEntry registeredRoute = routeCatalog.findByRouteId(resolvedRoute.routeId())
                .filter(resolvedRoute::equals)
                .orElseThrow(() -> new IllegalArgumentException(
                        "resolvedRoute must be a current source-controlled route manifest entry"));
        return new RouteObservation(
                registeredRoute.routeId(),
                registeredRoute.deployment(),
                registeredRoute.httpMethod(),
                registeredRoute.path(),
                registeredRoute.canonicalAction(),
                registeredRoute);
    }

    private static String bestMatchingPath(HttpServletRequest request) {
        if (request == null) {
            return "/unknown";
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String matchingPattern && !matchingPattern.isBlank()) {
            return matchingPattern;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null || requestUri.isBlank()) {
            return "/unknown";
        }
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static CredentialObservation credentialObservation(RequestNames names) {
        boolean appKey = names.headerPresent(HEADER_CLIENT_APP_KEY);
        boolean appSecret = names.headerPresent(HEADER_CLIENT_APP_SECRET);
        boolean appAccessToken = names.headerPresent(HEADER_CLIENT_APP_ACCESS_TOKEN);
        boolean workerId = names.headerPresent(HEADER_WORKER_ID);
        boolean workerCredential = names.headerPresent(HEADER_WORKER_CREDENTIAL);
        boolean workerLeaseId = names.headerPresent(HEADER_WORKER_LEASE_ID);
        boolean workerComplete = workerId && workerCredential && workerLeaseId;
        boolean taskToken = names.headerPresent(HEADER_TASK_SCOPED_TOKEN);

        List<CredentialObservation> independentSources = new ArrayList<>();
        if (names.headerPresent(HEADER_UPSTREAM_ADMIN)) {
            independentSources.add(observed(AuthorizationPrincipalType.UPSTREAM_SYSTEM_ADMIN,
                    AuthorizationCredentialLane.LEGACY_UPSTREAM_ADMIN, "legacy-upstream-admin"));
        }
        if (names.headerPresent(HEADER_CLIENT_APP_CONTROL)) {
            independentSources.add(observed(AuthorizationPrincipalType.CLIENT_APP,
                    AuthorizationCredentialLane.CLIENT_APP_CONTROL, "client-app-control"));
        }
        if (appKey && appSecret) {
            independentSources.add(observed(AuthorizationPrincipalType.CLIENT_APP,
                    AuthorizationCredentialLane.CLIENT_APP_RUNTIME_CREDENTIAL, "client-app-runtime-credential"));
        }
        if (appKey && appAccessToken) {
            independentSources.add(observed(AuthorizationPrincipalType.CLIENT_APP,
                    AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS, "client-app-runtime-access"));
        }
        if (workerComplete) {
            independentSources.add(observed(AuthorizationPrincipalType.WORKER_PRINCIPAL,
                    AuthorizationCredentialLane.WORKER_CREDENTIAL, "worker-principal"));
        } else if (taskToken) {
            independentSources.add(observed(AuthorizationPrincipalType.TASK_CAPABILITY,
                    AuthorizationCredentialLane.TASK_SCOPED_TOKEN, "task-capability"));
        }
        if (navigatorUserObserved(names)) {
            independentSources.add(observed(AuthorizationPrincipalType.NAVIGATOR_USER,
                    AuthorizationCredentialLane.NAVIGATOR_JWT, "navigator-user-context"));
        }

        if (independentSources.size() > 1) {
            return conflict();
        }
        if (independentSources.size() == 1) {
            return independentSources.get(0);
        }
        return unknown();
    }

    private static boolean navigatorUserObserved(RequestNames names) {
        if (UserContext.getCurrentUser() == null) {
            return false;
        }
        return names.headerPresent(HEADER_AUTHORIZATION) || names.headerPresent(HEADER_API_KEY)
                || names.queryParameterPresent(QUERY_AUTH_TOKEN);
    }

    private static CredentialObservation observed(AuthorizationPrincipalType principalType,
                                                   AuthorizationCredentialLane credentialLane,
                                                   String observedSource) {
        return new CredentialObservation(principalType, credentialLane,
                "observed-" + observedSource, "observed", AuthorizationResolutionState.UNVERIFIED, false);
    }

    private static CredentialObservation conflict() {
        return new CredentialObservation(AuthorizationPrincipalType.UNKNOWN, AuthorizationCredentialLane.UNKNOWN,
                "credential-source-conflict", "conflict", AuthorizationResolutionState.CONFLICT, true);
    }

    private static CredentialObservation unknown() {
        return new CredentialObservation(AuthorizationPrincipalType.UNKNOWN, AuthorizationCredentialLane.UNKNOWN,
                "credential-source-unresolved", "unknown", AuthorizationResolutionState.UNKNOWN, false);
    }

    private static RequestNames requestNames(HttpServletRequest request) {
        if (request == null) {
            return new RequestNames(Set.of(), Set.of(), false);
        }
        Set<String> headerNames = normalizedNames(request.getHeaderNames());
        Set<String> queryParameterNames = normalizedNames(request.getParameterNames());
        boolean identityOverrideAttempt = Boolean.TRUE.equals(
                request.getAttribute(DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE))
                || headerNames.stream()
                .anyMatch(DeploymentIdentityResolver::isServerOwnedIdentityOverrideAttempt)
                || queryParameterNames.stream()
                .anyMatch(DeploymentIdentityResolver::isServerOwnedIdentityOverrideAttempt);
        return new RequestNames(headerNames, queryParameterNames, identityOverrideAttempt);
    }

    private static Set<String> normalizedNames(Enumeration<String> names) {
        if (names == null) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String correlationId(HttpServletRequest request) {
        Object existing = request == null ? null : request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }
        String correlationId = UUID.randomUUID().toString();
        if (request != null) {
            request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        }
        return correlationId;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record RequestNames(Set<String> headerNames,
                                Set<String> queryParameterNames,
                                boolean identityOverrideAttempt) {

        private boolean headerPresent(String headerName) {
            return headerNames.contains(headerName);
        }

        private boolean queryParameterPresent(String parameterName) {
            return queryParameterNames.contains(parameterName);
        }
    }

    private record RouteObservation(String routeId,
                                    String deployment,
                                    String httpMethod,
                                    String path,
                                    String actionId,
                                    AuthorizationRouteManifestEntry entry) {
    }

    private record CredentialObservation(AuthorizationPrincipalType principalType,
                                         AuthorizationCredentialLane credentialLane,
                                         String principalReference,
                                         String assurance,
                                         AuthorizationResolutionState resolutionState,
                                         boolean sourceConflict) {

        private String credentialReference() {
            return principalReference;
        }
    }
}

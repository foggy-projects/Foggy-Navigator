package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationEvaluationMode;
import com.foggy.navigator.common.authorization.AuthorizationSchemaV1;
import com.foggy.navigator.common.authorization.ManagementActionSetRegistry;
import com.foggy.navigator.common.authorization.ManagementAuthenticationContext;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.common.authorization.TypedManagementAuthenticationRequest;
import com.foggy.navigator.common.authorization.TypedManagementAuthorizationResult;
import com.foggy.navigator.common.authorization.TypedManagementCredentialSource;
import com.foggy.navigator.common.authorization.TypedManagementIngressAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical ingress boundary for typed management endpoints.
 *
 * <p>This interceptor only establishes the HTTP boundary and delegates all
 * source conflict, verifier, credential, token and policy evaluation to the
 * common typed-management facade. It deliberately does not fall back to JWT,
 * legacy, ClientApp, task or Worker credential handling.</p>
 */
@Component
public class TypedManagementAuthInterceptor implements HandlerInterceptor {

    private static final String MANAGEMENT_NAMESPACE = "/api/v1/management/v1/auth";
    private static final String TYPED_MANAGEMENT_SURFACE = "TYPED_MANAGEMENT_AUTH";
    private static final String CANONICAL_ENFORCE_MIGRATION_MODE = "CANONICAL_ENFORCE";
    private static final String PRINCIPAL_CREDENTIAL_HEADER = "X-Navi-Principal-Credential";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String AUTH_CONTEXT_ATTRIBUTE = ManagementAuthenticationContext.class.getName();
    private static final String CORRELATION_ID_ATTRIBUTE =
            TypedManagementAuthInterceptor.class.getName() + ".correlationId";

    private static final Set<String> APPROVED_ROUTE_KEYS = Set.of(
            "POST " + MANAGEMENT_NAMESPACE + "/exchange",
            "POST " + MANAGEMENT_NAMESPACE + "/security-actions/authorize",
            "GET " + MANAGEMENT_NAMESPACE + "/whoami",
            "GET " + MANAGEMENT_NAMESPACE + "/permissions",
            "POST " + MANAGEMENT_NAMESPACE + "/explain"
    );

    /*
     * These are known legacy/control/runtime/task/Worker credential carriers.
     * A typed management request containing any one of them is passed to the
     * canonical facade as a conflict; this class never assigns a precedence.
     */
    private static final List<String> PROHIBITED_CREDENTIAL_HEADERS = List.of(
            "X-Navi-Admin-Key",
            "X-Navi-Admin-Api-Key",
            "X-Navi-Operator-Key",
            "X-Navi-Operator-Api-Key",
            "X-Client-App-Control-Key",
            "X-Client-App-Key",
            "X-Client-App-Secret",
            "X-Client-App-Access-Token",
            "X-App-Key",
            "X-App-Secret",
            "X-App-Access-Token",
            "X-Foggy-App-Key",
            "X-Foggy-App-Secret",
            "X-Foggy-App-Access-Token",
            "X-Task-Scoped-Token",
            "X-Navigator-Worker-Id",
            "X-Navigator-Worker-Credential",
            "X-Navigator-Worker-Lease-Id",
            "X-Worker-Id",
            "X-API-Key"
    );

    private final AuthorizationRouteCatalog routeCatalog;
    private final ManagementActionSetRegistry managementActionSetRegistry;
    private final ObjectProvider<TypedManagementIngressAuthorizer> authorizerProvider;

    public TypedManagementAuthInterceptor(
            AuthorizationRouteCatalog routeCatalog,
            ManagementActionSetRegistry managementActionSetRegistry,
            ObjectProvider<TypedManagementIngressAuthorizer> authorizerProvider
    ) {
        this.routeCatalog = routeCatalog;
        this.managementActionSetRegistry = managementActionSetRegistry;
        this.authorizerProvider = authorizerProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        request.removeAttribute(AUTH_CONTEXT_ATTRIBUTE);

        final AuthorizationRouteManifestEntry route;
        try {
            route = resolveRegisteredRoute(request);
        } catch (RuntimeException ignored) {
            // A catalog or fixed action-set lookup failure is never an excuse
            // to bypass the typed-management boundary.
            deny(response, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
            return false;
        }
        if (route == null) {
            deny(response, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
            return false;
        }

        HeaderSelection principalCredential = singleHeader(request, PRINCIPAL_CREDENTIAL_HEADER);
        HeaderSelection authorization = singleHeader(request, AUTHORIZATION_HEADER);
        boolean prohibitedSourcePresent = principalCredential.isAmbiguous()
                || authorization.isAmbiguous()
                || hasProhibitedCredentialSource(request);

        TypedManagementAuthenticationRequest authenticationRequest =
                TypedManagementAuthenticationRequest.fromHttpHeaders(
                        route.routeId(),
                        route.canonicalAction(),
                        correlationId(request),
                        principalCredential.value(),
                        authorization.value(),
                        prohibitedSourcePresent);

        final TypedManagementIngressAuthorizer authorizer;
        try {
            authorizer = authorizerProvider == null ? null : authorizerProvider.getIfUnique();
        } catch (RuntimeException ignored) {
            deny(response, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
            return false;
        }
        if (authorizer == null) {
            deny(response, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
            return false;
        }

        final TypedManagementAuthorizationResult result;
        try {
            result = authorizer.authorize(authenticationRequest);
        } catch (RuntimeException ignored) {
            // Resolver/verifier failures must not disclose implementation or credential material.
            deny(response, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
            return false;
        }

        if (!isBoundCanonicalAllow(result, route, authenticationRequest)) {
            deny(response, stableReason(result));
            return false;
        }

        request.setAttribute(AUTH_CONTEXT_ATTRIBUTE, result.authenticationContext());
        return true;
    }

    /**
     * The typed-management guard is the enforcement boundary, so an
     * implementation cannot turn an arbitrary safe-looking context into an
     * allow.  The result must carry the exact binding enforcement decision
     * that corresponds to the registered route and the context it returns.
     */
    private static boolean isBoundCanonicalAllow(TypedManagementAuthorizationResult result,
                                                  AuthorizationRouteManifestEntry route,
                                                  TypedManagementAuthenticationRequest request) {
        if (result == null || route == null || request == null || !result.allowed() || result.reasonCode() != null
                || result.authenticationContext() == null || result.decision() == null) {
            return false;
        }
        ManagementAuthenticationContext context = result.authenticationContext();
        PolicyDecisionV1 decision = result.decision();
        TypedManagementCredentialSource requestCredentialSource = requestCredentialSource(request);
        if (requestCredentialSource == TypedManagementCredentialSource.NONE) {
            return false;
        }
        return result.credentialSource() == requestCredentialSource
                && context.credentialSource() == requestCredentialSource
                && AuthorizationSchemaV1.SCHEMA_VERSION.equals(decision.schemaVersion())
                && AuthorizationSchemaV1.POLICY_VERSION.equals(decision.policyVersion())
                && AuthorizationSchemaV1.ACTION_CATALOG_VERSION.equals(decision.actionCatalogVersion())
                && !blank(decision.serverBuild())
                && !blank(decision.decisionId())
                && decision.evaluatedAt() != null
                && decision.evaluationMode() == AuthorizationEvaluationMode.ENFORCEMENT
                && decision.decision() == AuthorizationDecisionOutcome.ALLOW
                && decision.reasonCode() == null
                && !decision.nonBinding()
                && route.routeId().equals(context.routeId())
                && route.canonicalAction().equals(context.actionId())
                && route.routeId().equals(decision.routeId())
                && route.canonicalAction().equals(decision.actionId())
                && !blank(context.correlationId())
                && context.correlationId().equals(decision.correlationId())
                && context.correlationId().equals(request.correlationId());
    }

    /**
     * A successful result must be bound to exactly the one typed credential
     * carrier that arrived with this request.  Keep this check in the HTTP
     * boundary as a defence against a future facade wiring or resolver
     * regression; it is intentionally not a source-precedence rule.
     */
    private static TypedManagementCredentialSource requestCredentialSource(
            TypedManagementAuthenticationRequest request
    ) {
        if (request.prohibitedCredentialSourcePresent() || request.malformedTypedCredentialPresentation()) {
            return TypedManagementCredentialSource.NONE;
        }
        boolean principalPresent = request.principalCredential() != null;
        boolean bearerPresent = request.managementBearer() != null;
        if (principalPresent == bearerPresent) {
            return TypedManagementCredentialSource.NONE;
        }
        return principalPresent
                ? TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                : TypedManagementCredentialSource.MANAGEMENT_BEARER;
    }

    private AuthorizationRouteManifestEntry resolveRegisteredRoute(HttpServletRequest request) {
        if (request == null || routeCatalog == null || managementActionSetRegistry == null) {
            return null;
        }
        String path = requestPath(request);
        String method = request.getMethod();
        if (path == null || method == null || !APPROVED_ROUTE_KEYS.contains(method.toUpperCase(Locale.ROOT) + " " + path)) {
            return null;
        }
        String expectedRouteId = AuthorizationRouteCatalog.routeId(
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER, method, path);
        AuthorizationRouteManifestEntry route = routeCatalog
                .findByDeploymentMethodAndPath(AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER, method, path)
                .orElse(null);
        if (route == null || blank(route.routeId()) || blank(route.canonicalAction())
                || !expectedRouteId.equals(route.routeId())
                || !AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER.equals(route.deployment())
                || !method.equalsIgnoreCase(route.httpMethod()) || !path.equals(route.path())
                || !TYPED_MANAGEMENT_SURFACE.equals(route.surface())
                || !CANONICAL_ENFORCE_MIGRATION_MODE.equals(route.migrationMode())
                || !managementActionSetRegistry.isRegisteredEndpointAction(route.routeId(), route.canonicalAction())) {
            return null;
        }
        return route;
    }

    private static HeaderSelection singleHeader(HttpServletRequest request, String headerName) {
        if (request == null) {
            return HeaderSelection.absent();
        }
        Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return HeaderSelection.absent();
        }
        String first = values.nextElement();
        if (values.hasMoreElements()) {
            return HeaderSelection.ambiguousSelection();
        }
        return new HeaderSelection(first, false);
    }

    private static boolean hasProhibitedCredentialSource(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        /*
         * Typed management has no query-string contract. Reject every query
         * parameter, including an empty one, so an unrecognised alias cannot
         * become a credential precedence or proxy-normalisation bypass later.
         * The canonical facade records this as a source conflict; do not add
         * a per-parameter allow-list here.
         */
        Enumeration<String> parameterNames = request.getParameterNames();
        if (parameterNames != null && parameterNames.hasMoreElements()) {
            return true;
        }
        for (String header : PROHIBITED_CREDENTIAL_HEADERS) {
            Enumeration<String> values = request.getHeaders(header);
            if (values != null && values.hasMoreElements()) {
                return true;
            }
        }
        return false;
    }

    private static String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (blank(requestUri)) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (!blank(contextPath) && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static String correlationId(HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String correlationId = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        return correlationId;
    }

    private static AuthorizationReasonCode stableReason(TypedManagementAuthorizationResult result) {
        return result != null && result.reasonCode() != null
                ? result.reasonCode()
                : AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
    }

    private static void deny(HttpServletResponse response, AuthorizationReasonCode reason) throws IOException {
        AuthorizationReasonCode stableReason = reason == null
                ? AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID
                : reason;
        response.setStatus(httpStatus(stableReason));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"reasonCode\":\"" + stableReason.name() + "\"}");
    }

    private static int httpStatus(AuthorizationReasonCode reason) {
        return reason.name().startsWith("AUTHN_")
                ? HttpServletResponse.SC_UNAUTHORIZED
                : HttpServletResponse.SC_FORBIDDEN;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record HeaderSelection(String value, boolean isAmbiguous) {

        private static HeaderSelection absent() {
            return new HeaderSelection(null, false);
        }

        private static HeaderSelection ambiguousSelection() {
            return new HeaderSelection(null, true);
        }
    }
}

package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server-owned proof of the authenticated Navigator MVC ingress for a command.
 *
 * <p>The returned value contains only stable identity metadata. It never retains the servlet
 * request or any bearer token, API key, query token, business payload, or execution capability.</p>
 */
@Service
final class TrustedNavigatorCommandIngressAuthority {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String QUERY_TOKEN = "token";
    private static final String API_KEY = "X-API-Key";
    private static final String JWT_FINGERPRINT_DOMAIN =
            "navi.navigator-jwt-principal-fingerprint.v1";
    private static final String API_KEY_FINGERPRINT_DOMAIN =
            "navi.navigator-api-key-principal-fingerprint.v1";
    private static final Pattern STRICT_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final List<String> FOREIGN_CREDENTIAL_HEADERS = List.of(
            "X-Navigator-API-Key",
            "X-Sharing-Key",
            "X-Navi-Principal-Credential",
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
            "X-Task-Token",
            "X-Task-Scoped-Token",
            "X-Worker-Token",
            "X-Navigator-Worker-Id",
            "X-Navigator-Worker-Credential",
            "X-Navigator-Worker-Lease-Id",
            "X-Worker-Id",
            "X-Platform-Admin-Key",
            "X-System-Admin-Key",
            "X-Operator-Token",
            "X-Principal-Token",
            "X-TMS-Agent-Token");

    /** Routing probe only. A true result is not an authorization decision. */
    boolean routingOnlyHasCurrentCredentialCandidate() {
        HttpServletRequest request = currentServletRequest();
        return request != null && hasNavigatorCredentialCandidate(request);
    }

    /** Routing probe only. A true result is not an authorization decision. */
    boolean routingOnlyCurrentRequestMatches(String method, String routeId) {
        HttpServletRequest request = currentServletRequest();
        if (request == null || !Objects.equals(method, request.getMethod())) {
            return false;
        }
        return Objects.equals(routeId, request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    }

    VerifiedIngress require(
            AgentResolveContext context,
            List<IngressDescriptor> allowed,
            String routeSourceConflictCode) {
        Objects.requireNonNull(allowed, "allowed ingresses must not be null");
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("allowed ingresses must not be empty");
        }
        HttpServletRequest request = Objects.requireNonNull(
                currentServletRequest(), "trusted Navigator MVC request is unavailable");
        NavigatorCredentialSource credentialSource = requireNavigatorCredentialSource(request);
        rejectForeignCredentials(request, credentialSource);

        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null || isBlank(currentUser.getUserId())) {
            throw rejected("TRUSTED_NAVIGATOR_CURRENT_USER_MISSING");
        }
        requireAttribute(request, "userId", currentUser.getUserId());
        requireAttribute(request, "username", currentUser.getUsername());
        requireAttribute(request, "tenantId", currentUser.getTenantId());
        requireAttribute(request, "roles", currentUser.getRoles());
        if (context == null
                || !Objects.equals(currentUser.getUserId(), context.getUserId())
                || !Objects.equals(currentUser.getTenantId(), context.getTenantId())) {
            throw rejected("TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT");
        }
        if (!"POST".equals(request.getMethod())) {
            throw rejected("TRUSTED_NAVIGATOR_HTTP_METHOD_CONFLICT");
        }

        Object routeAttribute = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttribute == null ? null : routeAttribute.toString();
        String source = context.getRequestSource();
        IngressDescriptor matched = allowed.stream()
                .filter(expected -> expected.routeId().equals(route)
                        && expected.requestSource().equals(source))
                .findFirst()
                .orElseThrow(() -> rejected(routeSourceConflictCode));
        return new VerifiedIngress(
                matched,
                currentUser.getUserId(),
                currentUser.getTenantId(),
                credentialSource.lane,
                principalFingerprint(
                        currentUser.getUserId(), credentialSource.fingerprintDomain));
    }

    /** CREATE-compatible identity policy: both absent and blank values mint a fresh UUID. */
    String canonicalCreateClientRequestId(@Nullable String supplied) {
        return canonicalClientRequestId(supplied);
    }

    /** TERMINATE-compatible identity policy: both absent and blank values mint a fresh UUID. */
    String canonicalTerminationClientRequestId(@Nullable String supplied) {
        return canonicalClientRequestId(supplied);
    }

    private static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.trim();
        if (!STRICT_UUID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("clientRequestId must be a canonical UUID");
        }
        return UUID.fromString(trimmed).toString();
    }

    private static NavigatorCredentialSource requireNavigatorCredentialSource(
            HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION);
        String queryToken = request.getParameter(QUERY_TOKEN);
        String apiKey = request.getHeader(API_KEY);
        boolean bearerCandidate = authorization != null
                && authorization.startsWith(BEARER_PREFIX);
        boolean queryCandidate = queryToken != null && !queryToken.isEmpty();
        boolean apiKeyCandidate = apiKey != null;
        int candidates = (bearerCandidate ? 1 : 0)
                + (queryCandidate ? 1 : 0)
                + (apiKeyCandidate ? 1 : 0);
        if (candidates > 1) {
            throw rejected("TRUSTED_NAVIGATOR_MIXED_AUTHORIZATION");
        }
        if (bearerCandidate) {
            if (isBlank(authorization.substring(BEARER_PREFIX.length()))) {
                throw rejected("TRUSTED_NAVIGATOR_BEARER_MISSING");
            }
            return NavigatorCredentialSource.BEARER;
        }
        if (queryCandidate) {
            if (isBlank(queryToken)) {
                throw rejected("TRUSTED_NAVIGATOR_QUERY_TOKEN_MISSING");
            }
            return NavigatorCredentialSource.QUERY_TOKEN;
        }
        if (apiKeyCandidate) {
            if (isBlank(apiKey)) {
                throw rejected("TRUSTED_NAVIGATOR_API_KEY_MISSING");
            }
            return NavigatorCredentialSource.API_KEY;
        }
        throw rejected("TRUSTED_NAVIGATOR_CREDENTIAL_SOURCE_MISSING");
    }

    private static void rejectForeignCredentials(
            HttpServletRequest request,
            NavigatorCredentialSource credentialSource) {
        String authorization = request.getHeader(AUTHORIZATION);
        String queryToken = request.getParameter(QUERY_TOKEN);
        String apiKey = request.getHeader(API_KEY);
        if ((credentialSource != NavigatorCredentialSource.BEARER
                && !isBlank(authorization))
                || (credentialSource != NavigatorCredentialSource.QUERY_TOKEN
                && !isBlank(queryToken))
                || (credentialSource != NavigatorCredentialSource.API_KEY
                && !isBlank(apiKey))) {
            throw rejected("TRUSTED_NAVIGATOR_MIXED_AUTHORIZATION");
        }
        for (String header : FOREIGN_CREDENTIAL_HEADERS) {
            if (!isBlank(request.getHeader(header))) {
                throw rejected("TRUSTED_NAVIGATOR_MIXED_CREDENTIAL_LANE");
            }
        }
    }

    private static void requireAttribute(
            HttpServletRequest request,
            String name,
            @Nullable String expected) {
        if (!Objects.equals(expected, request.getAttribute(name))) {
            throw rejected("TRUSTED_NAVIGATOR_AUTH_ATTRIBUTE_CONFLICT");
        }
    }

    private static boolean hasNavigatorCredentialCandidate(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return true;
        }
        String queryToken = request.getParameter(QUERY_TOKEN);
        if (queryToken != null && !queryToken.isEmpty()) {
            return true;
        }
        return request.getHeader(API_KEY) != null;
    }

    @Nullable
    private static HttpServletRequest currentServletRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest() : null;
    }

    private static String principalFingerprint(String userId, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            updateDigestField(digest, userId);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static SecurityException rejected(String safeCode) {
        return new SecurityException(safeCode);
    }

    enum IngressDescriptor {
        TASK_CREATE_DIRECT(
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                "NAVIGATOR_UI",
                "/api/v1/tasks",
                "UI"),
        TASK_TERMINATE_DIRECT(
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                "NAVIGATOR_UI",
                "/api/v1/tasks/{taskId}/cancel",
                "UI"),
        TRANSITIONAL_AGENT_ASK(
                CanonicalCommandEnvelope.CommandIngress.A2A,
                "NAVIGATOR_A2A",
                "/api/v1/agents/{agentId}/ask",
                "UI"),
        A2A_TASK_CREATE(
                CanonicalCommandEnvelope.CommandIngress.A2A,
                "NAVIGATOR_A2A",
                "/api/v1/agents/{agentId}/ask",
                "A2A"),
        A2A_TASK_TERMINATE(
                CanonicalCommandEnvelope.CommandIngress.A2A,
                "NAVIGATOR_A2A",
                "/api/v1/agents/{agentId}/tasks/{taskId}/cancel",
                "A2A"),
        OPEN_API_MANAGEMENT_TASK_TERMINATE(
                CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                "NAVIGATOR_OPEN_API",
                "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel",
                "OPEN_API"),
        SESSION_FORWARD_CREATE(
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                "NAVIGATOR_UI_FORWARD",
                "/api/v1/session-relations/forward",
                "UI_FORWARD");

        private final CanonicalCommandEnvelope.CommandIngress commandIngress;
        private final String clientSurface;
        private final String routeId;
        private final String requestSource;

        IngressDescriptor(
                CanonicalCommandEnvelope.CommandIngress commandIngress,
                String clientSurface,
                String routeId,
                String requestSource) {
            this.commandIngress = Objects.requireNonNull(
                    commandIngress, "command ingress must not be null");
            requireText(clientSurface, "client surface");
            requireText(routeId, "route ID");
            requireText(requestSource, "request source");
            this.clientSurface = clientSurface;
            this.routeId = routeId;
            this.requestSource = requestSource;
        }

        CanonicalCommandEnvelope.CommandIngress commandIngress() {
            return commandIngress;
        }

        String clientSurface() {
            return clientSurface;
        }

        String routeId() {
            return routeId;
        }

        String requestSource() {
            return requestSource;
        }
    }

    record VerifiedIngress(
            IngressDescriptor descriptor,
            String ownerUserId,
            @Nullable String tenantId,
            AuthorizationCredentialLane credentialLane,
            String principalFingerprint) {
        VerifiedIngress {
            Objects.requireNonNull(descriptor, "ingress descriptor must not be null");
            requireText(ownerUserId, "owner user ID");
            Objects.requireNonNull(credentialLane, "credential lane must not be null");
            requireText(principalFingerprint, "principal fingerprint");
        }

        @Override
        public String toString() {
            return "VerifiedIngress[content-free]";
        }

        CanonicalCommandEnvelope.CommandIngress commandIngress() {
            return descriptor.commandIngress();
        }

        String clientSurface() {
            return descriptor.clientSurface();
        }

        String routeId() {
            return descriptor.routeId();
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private enum NavigatorCredentialSource {
        BEARER(AuthorizationCredentialLane.NAVIGATOR_JWT, JWT_FINGERPRINT_DOMAIN),
        QUERY_TOKEN(AuthorizationCredentialLane.NAVIGATOR_JWT, JWT_FINGERPRINT_DOMAIN),
        API_KEY(AuthorizationCredentialLane.NAVIGATOR_API_KEY, API_KEY_FINGERPRINT_DOMAIN);

        private final AuthorizationCredentialLane lane;
        private final String fingerprintDomain;

        NavigatorCredentialSource(
                AuthorizationCredentialLane lane,
                String fingerprintDomain) {
            this.lane = lane;
            this.fingerprintDomain = fingerprintDomain;
        }
    }
}

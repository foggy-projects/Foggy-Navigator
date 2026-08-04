package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedNavigatorCommandIngressAuthorityTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String ROUTE = "/api/v1/tasks";
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor EXPECTED =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.TASK_CREATE_DIRECT;

    private final TrustedNavigatorCommandIngressAuthority authority =
            new TrustedNavigatorCommandIngressAuthority();

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void bearerIngressReturnsOnlyServerDerivedContentFreeIdentity() {
        MockHttpServletRequest request = bindRequest();
        request.addHeader("Authorization", "Bearer raw-jwt-secret");

        TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified =
                authority.require(context(), List.of(EXPECTED), "ROUTE_CONFLICT");

        assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                verified.commandIngress());
        assertEquals("NAVIGATOR_UI", verified.clientSurface());
        assertEquals(ROUTE, verified.routeId());
        assertEquals(USER_ID, verified.ownerUserId());
        assertEquals(TENANT_ID, verified.tenantId());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                verified.credentialLane());
        assertEquals(64, verified.principalFingerprint().length());
        assertFalse(verified.principalFingerprint().contains("raw-jwt-secret"));
        assertEquals("VerifiedIngress[content-free]", verified.toString());
        assertTrue(authority.routingOnlyHasCurrentCredentialCandidate());
        assertTrue(authority.routingOnlyCurrentRequestMatches("POST", ROUTE));
        assertFalse(authority.routingOnlyCurrentRequestMatches("GET", ROUTE));
    }

    @Test
    void queryTokenAndApiKeyUseTheirExistingCredentialLanesAndDomains() {
        MockHttpServletRequest query = bindRequest();
        query.addParameter("token", "raw-query-secret");
        TrustedNavigatorCommandIngressAuthority.VerifiedIngress queryVerified =
                authority.require(context(), List.of(EXPECTED), "ROUTE_CONFLICT");

        MockHttpServletRequest api = bindRequest();
        api.addHeader("X-API-Key", "raw-api-secret");
        TrustedNavigatorCommandIngressAuthority.VerifiedIngress apiVerified =
                authority.require(context(), List.of(EXPECTED), "ROUTE_CONFLICT");

        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                queryVerified.credentialLane());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                apiVerified.credentialLane());
        assertNotEquals(queryVerified.principalFingerprint(),
                apiVerified.principalFingerprint());
        assertFalse(queryVerified.toString().contains("raw-query-secret"));
        assertFalse(apiVerified.toString().contains("raw-api-secret"));
    }

    @Test
    void mixedForeignBlankAndMissingCredentialsFailClosed() {
        MockHttpServletRequest mixed = bindRequest();
        mixed.addHeader("Authorization", "Bearer jwt");
        mixed.addHeader("X-API-Key", "api");
        assertSafeSecurity("TRUSTED_NAVIGATOR_MIXED_AUTHORIZATION");

        MockHttpServletRequest foreign = bindRequest();
        foreign.addHeader("Authorization", "Bearer jwt");
        foreign.addHeader("X-Sharing-Key", "sharing-secret");
        assertSafeSecurity("TRUSTED_NAVIGATOR_MIXED_CREDENTIAL_LANE");

        MockHttpServletRequest blankBearer = bindRequest();
        blankBearer.addHeader("Authorization", "Bearer  ");
        assertSafeSecurity("TRUSTED_NAVIGATOR_BEARER_MISSING");

        MockHttpServletRequest blankApiKey = bindRequest();
        blankApiKey.addHeader("X-API-Key", " ");
        assertSafeSecurity("TRUSTED_NAVIGATOR_API_KEY_MISSING");

        bindRequest();
        assertSafeSecurity("TRUSTED_NAVIGATOR_CREDENTIAL_SOURCE_MISSING");
    }

    @Test
    void ambientIdentityMethodAndRouteSourceMustRemainExact() {
        MockHttpServletRequest attributeDrift = bindRequest();
        attributeDrift.addHeader("Authorization", "Bearer jwt");
        attributeDrift.setAttribute("tenantId", "tenant-other");
        assertSafeSecurity("TRUSTED_NAVIGATOR_AUTH_ATTRIBUTE_CONFLICT");

        MockHttpServletRequest contextDrift = bindRequest();
        contextDrift.addHeader("Authorization", "Bearer jwt");
        SecurityException contextFailure = assertThrows(SecurityException.class,
                () -> authority.require(
                        AgentResolveContext.builder()
                                .userId(USER_ID)
                                .tenantId("tenant-other")
                                .requestSource("UI")
                                .build(),
                        List.of(EXPECTED),
                        "ROUTE_CONFLICT"));
        assertEquals("TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT",
                contextFailure.getMessage());

        MockHttpServletRequest missingContext = bindRequest();
        missingContext.addHeader("Authorization", "Bearer jwt");
        SecurityException missingContextFailure = assertThrows(SecurityException.class,
                () -> authority.require(null, List.of(EXPECTED), "ROUTE_CONFLICT"));
        assertEquals("TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT",
                missingContextFailure.getMessage());

        MockHttpServletRequest missingForwardContext = bindRequest(
                "POST", "/api/v1/session-relations/forward");
        missingForwardContext.addHeader("Authorization", "Bearer jwt");
        SecurityException missingForwardContextFailure = assertThrows(SecurityException.class,
                () -> authority.require(
                        null,
                        List.of(TrustedNavigatorCommandIngressAuthority.IngressDescriptor
                                .SESSION_FORWARD_CREATE),
                        "TRUSTED_NAVIGATOR_FORWARD_ROUTE_SOURCE_CONFLICT"));
        assertEquals("TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT",
                missingForwardContextFailure.getMessage());

        MockHttpServletRequest methodDrift = bindRequest("GET", ROUTE);
        methodDrift.addHeader("Authorization", "Bearer jwt");
        assertSafeSecurity("TRUSTED_NAVIGATOR_HTTP_METHOD_CONFLICT");

        MockHttpServletRequest routeDrift = bindRequest("POST", "/api/v1/other");
        routeDrift.addHeader("Authorization", "Bearer jwt");
        assertSafeSecurity("ROUTE_CONFLICT");

        MockHttpServletRequest sourceDrift = bindRequest();
        sourceDrift.addHeader("Authorization", "Bearer jwt");
        SecurityException sourceFailure = assertThrows(SecurityException.class,
                () -> authority.require(
                        AgentResolveContext.builder()
                                .userId(USER_ID)
                                .tenantId(TENANT_ID)
                                .requestSource("A2A")
                                .build(),
                        List.of(EXPECTED),
                        "ROUTE_CONFLICT"));
        assertEquals("ROUTE_CONFLICT", sourceFailure.getMessage());
    }

    @Test
    void clientRequestIdentityKeepsCreateCompatibility() {
        String canonical = authority.canonicalCreateClientRequestId(
                " 550E8400-E29B-41D4-A716-446655440000 ");
        String absent = authority.canonicalCreateClientRequestId(null);
        String blank = authority.canonicalCreateClientRequestId("  ");

        assertEquals("550e8400-e29b-41d4-a716-446655440000", canonical);
        assertEquals(UUID.fromString(absent).toString(), absent);
        assertEquals(UUID.fromString(blank).toString(), blank);
        assertNotEquals(absent, blank);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> authority.canonicalCreateClientRequestId("not-a-uuid"));
        assertEquals("clientRequestId must be a canonical UUID", invalid.getMessage());
    }

    @Test
    void openApiManagementTerminationDescriptorKeepsExistingCredentialLanes() {
        String route = "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel";
        TrustedNavigatorCommandIngressAuthority.IngressDescriptor descriptor =
                TrustedNavigatorCommandIngressAuthority.IngressDescriptor
                        .OPEN_API_MANAGEMENT_TASK_TERMINATE;
        MockHttpServletRequest request = bindRequest("POST", route);
        request.addParameter("token", "raw-query-secret");
        AgentResolveContext openApi = AgentResolveContext.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .requestSource("OPEN_API")
                .build();

        TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified =
                authority.require(openApi, List.of(descriptor), "ROUTE_CONFLICT");

        assertEquals(CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                verified.commandIngress());
        assertEquals("NAVIGATOR_OPEN_API", verified.clientSurface());
        assertEquals(route, verified.routeId());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                verified.credentialLane());

        MockHttpServletRequest wrongSource = bindRequest("POST", route);
        wrongSource.addHeader("X-API-Key", "raw-api-key-secret");
        SecurityException rejected = assertThrows(SecurityException.class,
                () -> authority.require(
                        context(), List.of(descriptor), "ROUTE_CONFLICT"));
        assertEquals("ROUTE_CONFLICT", rejected.getMessage());
    }

    private void assertSafeSecurity(String expectedCode) {
        SecurityException failure = assertThrows(SecurityException.class,
                () -> authority.require(context(), List.of(EXPECTED), "ROUTE_CONFLICT"));
        assertEquals(expectedCode, failure.getMessage());
    }

    private AgentResolveContext context() {
        return AgentResolveContext.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .requestSource("UI")
                .build();
    }

    private MockHttpServletRequest bindRequest() {
        return bindRequest("POST", ROUTE);
    }

    private MockHttpServletRequest bindRequest(String method, String route) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.setAttribute("userId", USER_ID);
        request.setAttribute("username", "foggy");
        request.setAttribute("tenantId", TENANT_ID);
        request.setAttribute("roles", "USER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .username("foggy")
                .tenantId(TENANT_ID)
                .roles("USER")
                .build());
        return request;
    }
}

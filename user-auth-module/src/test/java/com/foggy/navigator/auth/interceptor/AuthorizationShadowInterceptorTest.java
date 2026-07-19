package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.auth.authorization.LegacyAuthorizationContextAdapter;
import com.foggy.navigator.auth.authorization.AuthorizationIngressRouteResolver;
import com.foggy.navigator.common.authorization.AuthorizationContextV1;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationDecisionAuditStore;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.AuthorizationResolutionState;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.AuthorizationSchemaV1;
import com.foggy.navigator.common.authorization.AuthorizationShadowEvaluator;
import com.foggy.navigator.common.authorization.LegacyEnforcementOutcome;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthorizationShadowInterceptorTest {

    @Mock
    private LegacyAuthorizationContextAdapter contextAdapter;

    @Mock
    private AuthorizationIngressRouteResolver ingressRouteResolver;

    @Mock
    private AuthorizationShadowEvaluator shadowEvaluator;

    @Mock
    private AuthorizationDecisionAuditStore auditStore;

    private AuthorizationShadowInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthorizationShadowInterceptor(
                contextAdapter,
                ingressRouteResolver,
                shadowEvaluator,
                auditStore);
    }

    @Test
    void observesCompletedLegacyResponseWithoutChangingStatusBodyOrSideEffect() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerMethod();
        AuthorizationContextV1 context = registeredContext();
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN);
        AtomicInteger legacySideEffect = new AtomicInteger();

        when(contextAdapter.adapt(request)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);

        assertTrue(interceptor.preHandle(request, response, handler));
        legacySideEffect.incrementAndGet();
        response.setStatus(202);
        response.getWriter().write("legacy-body");

        interceptor.afterCompletion(request, response, handler, null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.ALLOW, 202);
        assertEquals(202, response.getStatus());
        assertEquals("legacy-body", response.getContentAsString());
        assertEquals(1, legacySideEffect.get());
    }

    @Test
    void mapsFinalDenyAndNonHttpStatusToStableLegacyOutcomes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        HandlerMethod handler = handlerMethod();
        AuthorizationContextV1 context = registeredContext();
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN);
        when(contextAdapter.adapt(request)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, denied, handler));
        denied.setStatus(403);
        interceptor.afterCompletion(request, denied, handler, null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.DENY, 403);

        MockHttpServletRequest unknownRequest = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        when(contextAdapter.adapt(unknownRequest)).thenReturn(context);
        assertTrue(interceptor.preHandle(unknownRequest, unknown, handler));
        unknown.setStatus(99);
        interceptor.afterCompletion(unknownRequest, unknown, handler, null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.UNKNOWN, 99);
    }

    @Test
    void observesLegacyRxEnvelopeOutcomeWithoutChangingResponseStatusOrBody() throws Exception {
        AuthorizationContextV1 context = registeredContext();
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN);
        when(contextAdapter.adapt(any(HttpServletRequest.class))).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);
        EnvelopeController controller = new EnvelopeController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthorizationShadowResponseBodyAdvice())
                .addInterceptors(interceptor)
                .build();

        mockMvc.perform(get("/shadow-envelope/failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(600))
                .andExpect(jsonPath("$.exCode").value("A600"));

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.UNKNOWN, 200);
        assertEquals(1, controller.failureSideEffects());

        mockMvc.perform(get("/shadow-envelope/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("legacy-success-payload"));

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.ALLOW, 200);

        mockMvc.perform(get("/shadow-envelope/http-deny"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("legacy-success-payload"));

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.DENY, 500);
    }

    @Test
    void sendsUnregisteredRequestToTheStoreWithoutChangingLegacyHandling() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/unregistered/client-supplied-path");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerMethod();
        AuthorizationContextV1 context = context("unregistered-route", "unregistered.action",
                "/unregistered/client-supplied-path", "raw-principal-input", "raw-credential-input");
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        when(contextAdapter.adapt(request)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);

        assertTrue(interceptor.preHandle(request, response, handler));
        response.setStatus(401);
        interceptor.afterCompletion(request, response, handler, null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.DENY, 401);
    }

    @Test
    void ignoresNonHandlerMethodsAndSwallowsShadowOrAuditFailures() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object resourceHandler = new Object();

        assertTrue(interceptor.preHandle(request, response, resourceHandler));
        interceptor.afterCompletion(request, response, resourceHandler, null);
        verify(contextAdapter, never()).adapt(any());

        HandlerMethod handler = handlerMethod();
        AuthorizationContextV1 context = registeredContext();
        when(contextAdapter.adapt(request)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenThrow(new IllegalStateException("shadow failure"));
        response.setStatus(201);
        response.getWriter().write("legacy-body");
        assertTrue(interceptor.preHandle(request, response, handler));
        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, handler,
                new IllegalStateException("legacy failure")));
        assertEquals(201, response.getStatus());
        assertEquals("legacy-body", response.getContentAsString());
        verify(auditStore, never()).appendShadow(any(), any(), any(), anyInt());

        MockHttpServletRequest auditFailureRequest = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse auditFailureResponse = new MockHttpServletResponse();
        when(contextAdapter.adapt(auditFailureRequest)).thenReturn(context);
        doReturn(PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN))
                .when(shadowEvaluator).evaluate(context);
        when(auditStore.appendShadow(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("audit failure"));
        auditFailureResponse.setStatus(204);
        assertTrue(interceptor.preHandle(auditFailureRequest, auditFailureResponse, handler));
        assertDoesNotThrow(() -> interceptor.afterCompletion(auditFailureRequest, auditFailureResponse, handler, null));
        assertEquals(204, auditFailureResponse.getStatus());
    }

    @Test
    void routeObservationFailureCannotRejectOrChangeALegacyRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerMethod();
        when(ingressRouteResolver.resolve(request)).thenThrow(new IllegalStateException("observer failure"));

        response.setStatus(202);
        response.getWriter().write("legacy-body");

        assertDoesNotThrow(() -> interceptor.preHandle(request, response, handler));
        interceptor.afterCompletion(request, response, handler, null);

        assertEquals(202, response.getStatus());
        assertEquals("legacy-body", response.getContentAsString());
        verify(contextAdapter, never()).adapt(any());
        verify(auditStore, never()).appendShadow(any(), any(), any(), anyInt());
    }

    @Test
    void observesResolvedWebSocketIngressWithoutTreatingDefaultStatusAsAllow() throws Exception {
        AuthorizationRouteManifestEntry webSocket = new AuthorizationRouteCatalog()
                .findByRouteId("websocket:connect:/api/v1/ssh/{sessionId}/ws")
                .orElseThrow();
        AuthorizationContextV1 context = context(webSocket.routeId(), webSocket.canonicalAction(),
                webSocket.path(), "raw-principal-input", "raw-credential-input");
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.UNKNOWN,
                AuthorizationReasonCode.AUTHZ_LEGACY_PRINCIPAL_UNVERIFIED);

        MockHttpServletRequest successfulRequest = webSocketRequest();
        MockHttpServletResponse successfulResponse = new MockHttpServletResponse();
        when(ingressRouteResolver.resolve(successfulRequest)).thenReturn(Optional.of(webSocket));
        when(contextAdapter.adapt(successfulRequest, webSocket)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);
        successfulResponse.setStatus(101);

        assertTrue(interceptor.preHandle(successfulRequest, successfulResponse, new Object()));
        interceptor.afterCompletion(successfulRequest, successfulResponse, new Object(), null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.ALLOW, 101);

        MockHttpServletRequest defaultStatusRequest = webSocketRequest();
        MockHttpServletResponse defaultStatusResponse = new MockHttpServletResponse();
        when(ingressRouteResolver.resolve(defaultStatusRequest)).thenReturn(Optional.of(webSocket));
        when(contextAdapter.adapt(defaultStatusRequest, webSocket)).thenReturn(context);
        defaultStatusResponse.setStatus(200);

        assertTrue(interceptor.preHandle(defaultStatusRequest, defaultStatusResponse, new Object()));
        interceptor.afterCompletion(defaultStatusRequest, defaultStatusResponse, new Object(), null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.UNKNOWN, 200);

        MockHttpServletRequest deniedRequest = webSocketRequest();
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        when(ingressRouteResolver.resolve(deniedRequest)).thenReturn(Optional.of(webSocket));
        when(contextAdapter.adapt(deniedRequest, webSocket)).thenReturn(context);
        deniedResponse.setStatus(403);

        assertTrue(interceptor.preHandle(deniedRequest, deniedResponse, new Object()));
        interceptor.afterCompletion(deniedRequest, deniedResponse, new Object(), null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.DENY, 403);
    }

    @Test
    void resolvedDisabledDiscoveryRootRemainsSidecarOnlyWhenAFrameworkDispatchOccurs() throws Exception {
        AuthorizationRouteManifestEntry actuatorRoot = new AuthorizationRouteCatalog()
                .findByRouteId("framework:get:/actuator")
                .orElseThrow();
        AuthorizationContextV1 context = context(actuatorRoot.routeId(), actuatorRoot.canonicalAction(),
                actuatorRoot.path(), "raw-principal-input", "raw-credential-input");
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_LEGACY_PRINCIPAL_UNVERIFIED);
        AuthorizationShadowInterceptor rootInterceptor = new AuthorizationShadowInterceptor(
                contextAdapter,
                new AuthorizationIngressRouteResolver(new AuthorizationRouteCatalog()),
                shadowEvaluator,
                auditStore);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(contextAdapter.adapt(request, actuatorRoot)).thenReturn(context);
        when(shadowEvaluator.evaluate(context)).thenReturn(decision);

        assertTrue(rootInterceptor.preHandle(request, response, new Object()));
        response.setStatus(404);
        response.getWriter().write("legacy-not-found");
        rootInterceptor.afterCompletion(request, response, new Object(), null);

        verify(auditStore).appendShadow(context, decision, LegacyEnforcementOutcome.DENY, 404);
        assertEquals(404, response.getStatus());
        assertEquals("legacy-not-found", response.getContentAsString());
    }

    private static HandlerMethod handlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new TestHandler(), TestHandler.class.getMethod("handle"));
    }

    private static MockHttpServletRequest webSocketRequest() {
        return new MockHttpServletRequest("GET", "/api/v1/ssh/session-42/ws");
    }

    private static AuthorizationContextV1 registeredContext() {
        return context("mvc:get:/api/v1/agents", "agent.list-agents", "/api/v1/agents",
                "raw-principal-input", "raw-credential-input");
    }

    private static AuthorizationContextV1 context(String routeId,
                                                   String actionId,
                                                   String path,
                                                   String principalReference,
                                                   String credentialReference) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                "correlation-1",
                new AuthorizationContextV1.Deployment("test-instance", "test", "CONFIGURED", false),
                new AuthorizationContextV1.Principal(AuthorizationPrincipalType.NAVIGATOR_USER,
                        principalReference, "observed", AuthorizationResolutionState.UNVERIFIED),
                new AuthorizationContextV1.Credential(AuthorizationCredentialLane.NAVIGATOR_JWT,
                        credentialReference, AuthorizationResolutionState.UNVERIFIED),
                new AuthorizationContextV1.Action(actionId),
                new AuthorizationContextV1.Route(routeId, AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                        "GET", path),
                new AuthorizationContextV1.Trust("legacy-unverified", AuthorizationResolutionState.UNVERIFIED),
                new AuthorizationContextV1.Target("test-target", AuthorizationResolutionState.UNVERIFIED),
                null,
                null,
                false,
                false);
    }

    static class TestHandler {
        public void handle() {
        }
    }

    @RestController
    @RequestMapping("/shadow-envelope")
    static class EnvelopeController {

        private final AtomicInteger failureSideEffects = new AtomicInteger();

        @GetMapping("/failure")
        RX<Void> failure() {
            failureSideEffects.incrementAndGet();
            return RX.failA("legacy business failure");
        }

        int failureSideEffects() {
            return failureSideEffects.get();
        }

        @GetMapping("/success")
        RX<String> success() {
            return RX.ok("legacy-success-payload");
        }

        @GetMapping("/http-deny")
        ResponseEntity<RX<String>> httpDeny() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RX.ok("legacy-success-payload"));
        }
    }
}

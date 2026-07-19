package com.foggy.navigator.auth.authorization;

import com.foggy.navigator.common.authorization.AuthorizationContextV1;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationResolutionState;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.DeploymentIdentity;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentitySource;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LegacyAuthorizationContextAdapterTest {

    @Mock
    private DeploymentIdentityProvider deploymentIdentityProvider;

    private LegacyAuthorizationContextAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(deploymentIdentityProvider.deploymentIdentity()).thenReturn(new DeploymentIdentity(
                "test-navigator-instance",
                "test",
                DeploymentIdentitySource.CONFIGURED,
                false));
        adapter = new LegacyAuthorizationContextAdapter(deploymentIdentityProvider, new AuthorizationRouteCatalog());
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void mapsUpstreamAdminToItsOnlyLegacyLaneWithoutRetainingCredentialMaterial() {
        MockHttpServletRequest request = registeredRequest();
        request.addHeader("X-Navi-Admin-Key", "opaque-input-not-retained");

        AuthorizationContextV1 context = adapter.adapt(request);

        assertObserved(context, AuthorizationPrincipalType.UPSTREAM_SYSTEM_ADMIN,
                AuthorizationCredentialLane.LEGACY_UPSTREAM_ADMIN);
        assertEquals("observed-legacy-upstream-admin", context.principal().principalReference());
        assertNotEquals("opaque-input-not-retained", context.credential().credentialReference());
        assertFalse(context.principal().principalType() == AuthorizationPrincipalType.INSTANCE_ROOT);
        assertFalse(context.principal().principalType() == AuthorizationPrincipalType.SAAS_PLATFORM);
    }

    @Test
    void mapsClientAppControlAndRuntimeLanesSeparately() {
        MockHttpServletRequest control = registeredRequest();
        control.addHeader("X-Client-App-Control-Key", "opaque-control-input");
        assertObserved(adapter.adapt(control), AuthorizationPrincipalType.CLIENT_APP,
                AuthorizationCredentialLane.CLIENT_APP_CONTROL);

        MockHttpServletRequest runtimeCredential = registeredRequest();
        runtimeCredential.addHeader("X-Client-App-Key", "opaque-runtime-key");
        runtimeCredential.addHeader("X-Client-App-Secret", "opaque-runtime-secret");
        assertObserved(adapter.adapt(runtimeCredential), AuthorizationPrincipalType.CLIENT_APP,
                AuthorizationCredentialLane.CLIENT_APP_RUNTIME_CREDENTIAL);

        MockHttpServletRequest runtimeAccess = registeredRequest();
        runtimeAccess.addHeader("X-Client-App-Key", "opaque-runtime-key");
        runtimeAccess.addHeader("X-Client-App-Access-Token", "opaque-runtime-access");
        assertObserved(adapter.adapt(runtimeAccess), AuthorizationPrincipalType.CLIENT_APP,
                AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS);
    }

    @Test
    void doesNotFabricateCapabilityWorkerRouteOrTypedAuthorityFromLegacyHeaderShape() {
        MockHttpServletRequest task = registeredRequest();
        task.addHeader("X-Task-Scoped-Token", "opaque-task-input");
        assertObserved(adapter.adapt(task), AuthorizationPrincipalType.TASK_CAPABILITY,
                AuthorizationCredentialLane.TASK_SCOPED_TOKEN);

        MockHttpServletRequest workerGateway = new MockHttpServletRequest("GET",
                "/internal/worker-gateway/v1/business-functions");
        workerGateway.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/internal/worker-gateway/v1/business-functions");
        workerGateway.addHeader("X-Task-Scoped-Token", "opaque-task-input");
        workerGateway.addHeader("X-Navigator-Worker-Id", "opaque-worker-id");
        workerGateway.addHeader("X-Navigator-Worker-Credential", "opaque-worker-credential");
        workerGateway.addHeader("X-Navigator-Worker-Lease-Id", "opaque-worker-lease");

        AuthorizationContextV1 context = adapter.adapt(workerGateway);

        assertObserved(context, AuthorizationPrincipalType.WORKER_PRINCIPAL,
                AuthorizationCredentialLane.WORKER_CREDENTIAL);
        assertNull(context.capability());
        assertNull(context.workerRoute());
        assertNull(context.authority());
        assertNull(context.delegation());
        assertNull(context.platformGrant());
        assertNull(context.tenantAuthority());

        MockHttpServletRequest runtimeAsk = new MockHttpServletRequest("POST", "/api/v1/open/agents/agent-1/ask");
        runtimeAsk.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/open/agents/{agentId}/ask");
        runtimeAsk.addHeader("X-Task-Scoped-Token", "opaque-task-input");

        assertNull(adapter.adapt(runtimeAsk).capability(),
                "a raw task header is not a verified runtime.ask capability section");
    }

    @Test
    void mapsNavigatorUserOnlyWhenTheExistingUserContextAndAUserCredentialSourceArePresent() {
        UserContext.setCurrentUser(CurrentUser.builder().userId("user-1").build());
        MockHttpServletRequest request = registeredRequest();
        request.addHeader("Authorization", "Bearer opaque-user-input");

        AuthorizationContextV1 context = adapter.adapt(request);

        assertObserved(context, AuthorizationPrincipalType.NAVIGATOR_USER,
                AuthorizationCredentialLane.NAVIGATOR_JWT);
    }

    @Test
    void reportsIndependentCredentialSourcesAsConflictRatherThanApplyingPrecedenceOrAnAuthorityUnion() {
        MockHttpServletRequest request = registeredRequest();
        request.addHeader("X-Navi-Admin-Key", "opaque-admin-input");
        request.addHeader("X-Client-App-Control-Key", "opaque-control-input");

        AuthorizationContextV1 context = adapter.adapt(request);

        assertTrue(context.credentialSourceConflict());
        assertEquals(AuthorizationPrincipalType.UNKNOWN, context.principal().principalType());
        assertEquals(AuthorizationCredentialLane.UNKNOWN, context.credential().credentialLane());
        assertEquals(AuthorizationResolutionState.CONFLICT, context.principal().resolutionState());
    }

    @Test
    void leavesPartialCredentialShapesUnknownAndUsesOnlyServerOwnedDeploymentIdentity() {
        MockHttpServletRequest request = registeredRequest();
        request.addHeader("X-Client-App-Key", "opaque-partial-input");
        request.addHeader("X-Navi-Instance-Id", "client-claimed-instance");
        request.addParameter("environmentProfile", "client-claimed-environment");
        request.addHeader("X-Navi-Upstream-System", "tms-x3");
        request.addHeader("X-Navi-Tenant", "tenant-a");

        AuthorizationContextV1 context = adapter.adapt(request);

        assertEquals(AuthorizationPrincipalType.UNKNOWN, context.principal().principalType());
        assertEquals(AuthorizationCredentialLane.UNKNOWN, context.credential().credentialLane());
        assertEquals("test-navigator-instance", context.deployment().navigatorInstanceId());
        assertEquals("test", context.deployment().environmentProfile());
        assertEquals(DeploymentIdentitySource.CONFIGURED.name(), context.deployment().source());
        assertTrue(context.deploymentIdentityOverrideAttempt());
    }

    @Test
    void adapterDoesNotReadRawBodyOrApplyBodySuppliedDeploymentIdentityFieldsWithoutObserverFlag() {
        MockHttpServletRequest request = registeredRequest();
        request.setContentType("application/json");
        request.setContent(("{\"navigatorInstanceId\":\"client-claimed-instance\","
                + "\"environmentProfile\":\"production\","
                + "\"tenantId\":\"client-claimed-tenant\","
                + "\"upstreamSystemId\":\"client-claimed-upstream\"}")
                .getBytes(StandardCharsets.UTF_8));

        AuthorizationContextV1 context = adapter.adapt(request);

        assertEquals("test-navigator-instance", context.deployment().navigatorInstanceId());
        assertEquals("test", context.deployment().environmentProfile());
        assertEquals(DeploymentIdentitySource.CONFIGURED.name(), context.deployment().source());
        assertFalse(context.deploymentIdentityOverrideAttempt(),
                "the adapter itself does not inspect raw body input; the JSON observer only supplies a boolean flag");
    }

    @Test
    void usesResolvedManifestMetadataInsteadOfTheIncomingMethodOrPath() {
        AuthorizationRouteManifestEntry sshWebSocket = new AuthorizationRouteCatalog()
                .findByRouteId("websocket:connect:/api/v1/ssh/{sessionId}/ws")
                .orElseThrow();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/request-controlled/path");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/request-controlled/{path}");

        AuthorizationContextV1 context = adapter.adapt(request, sshWebSocket);

        assertEquals(sshWebSocket.routeId(), context.route().routeId());
        assertEquals(sshWebSocket.deployment(), context.route().deployment());
        assertEquals(sshWebSocket.httpMethod(), context.route().httpMethod());
        assertEquals(sshWebSocket.path(), context.route().path());
        assertEquals(sshWebSocket.canonicalAction(), context.action().actionId());
    }

    @Test
    void refusesAResolvedEntryThatDoesNotMatchTheCurrentSourceControlledCatalog() {
        AuthorizationRouteManifestEntry registered = new AuthorizationRouteCatalog()
                .findByRouteId("framework:get:/actuator/info")
                .orElseThrow();
        AuthorizationRouteManifestEntry altered = new AuthorizationRouteManifestEntry(
                registered.routeId(),
                registered.deployment(),
                registered.httpMethod(),
                registered.path(),
                registered.surface(),
                registered.controllerMethod(),
                registered.source(),
                registered.currentGuard(),
                registered.currentTargetPredicate(),
                registered.canonicalAction(),
                registered.acceptedPrincipalLanes(),
                registered.targetResolver(),
                registered.riskTier(),
                registered.migrationMode(),
                registered.disposition(),
                registered.reviewStatus(),
                registered.notes() + " altered",
                registered.requiredSections());

        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(registeredRequest(), altered));
    }

    private static void assertObserved(AuthorizationContextV1 context,
                                       AuthorizationPrincipalType principalType,
                                       AuthorizationCredentialLane credentialLane) {
        assertEquals(principalType, context.principal().principalType());
        assertEquals(credentialLane, context.credential().credentialLane());
        assertEquals(AuthorizationResolutionState.UNVERIFIED, context.principal().resolutionState());
        assertEquals(AuthorizationResolutionState.UNVERIFIED, context.credential().resolutionState());
        assertFalse(context.credentialSourceConflict());
    }

    private static MockHttpServletRequest registeredRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health/external-surface");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/health/external-surface");
        return request;
    }
}

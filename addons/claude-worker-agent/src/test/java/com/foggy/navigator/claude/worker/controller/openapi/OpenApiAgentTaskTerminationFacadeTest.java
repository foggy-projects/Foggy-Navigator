package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.auth.aspect.AuthAspect;
import com.foggy.navigator.auth.interceptor.OpenApiAgentCancelCredentialCensus;
import com.foggy.navigator.claude.worker.model.dto.OpenApiTaskDTO;
import com.foggy.navigator.claude.worker.service.RuntimeTaskClosureService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.service.ScopedOpenApiManagementTaskTerminationCommandAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenApiAgentTaskTerminationFacadeTest {

    private static final String REQUEST_ID =
            "a4af5a56-c7c9-4c59-861d-19d7670b2254";

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void runtimeAccessAcceptedUsesOnlyB2a1AndReturnsNonterminalTruth() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);
        MockHttpServletRequest request = runtimeRequest();
        when(runtime.terminateAgentTaskWithRuntimeAccess(
                "app-key", "access-token", "upstream-user", REQUEST_ID,
                "agent-1", "task-1"))
                .thenReturn(new RuntimeTaskClosureService.AgentTerminationResult(
                        REQUEST_ID, "task-1", "agent-1", false, true, null));

        OpenApiTaskDTO result = facade.terminate(
                request, "agent-1", "task-1", REQUEST_ID);

        assertEquals(REQUEST_ID, result.getClientRequestId());
        assertEquals("task-1", result.getTaskId());
        assertEquals("agent-1", result.getAgentId());
        assertEquals("CANCEL_REQUESTED", result.getStatus());
        verifyNoInteractions(management);
    }

    @Test
    void runtimeCanonicalAbortedMapsToLegacyCancelledWithoutReread() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);
        MockHttpServletRequest request = runtimeRequest();
        when(runtime.terminateAgentTaskWithRuntimeAccess(
                "app-key", "access-token", "upstream-user", REQUEST_ID,
                "agent-1", "task-1"))
                .thenReturn(new RuntimeTaskClosureService.AgentTerminationResult(
                        REQUEST_ID, "task-1", "agent-1", false, false, "ABORTED"));

        OpenApiTaskDTO result = facade.terminate(
                request, "agent-1", "task-1", REQUEST_ID);

        assertEquals("CANCELLED", result.getStatus());
        verifyNoInteractions(management);
    }

    @Test
    void managementAlwaysTraversesRealRoleAspectBeforeB2a2() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        when(management.terminate("agent-1", "task-1", REQUEST_ID))
                .thenReturn(new ScopedOpenApiManagementTaskTerminationCommandAdapter
                        .TerminationResult("TERMINATION_REQUEST_ACCEPTED", null));
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);

        for (String roles : new String[]{"TENANT_ADMIN", "DEVELOPER", "SUPER_ADMIN"}) {
            UserContext.setCurrentUser(CurrentUser.builder()
                    .userId("manager")
                    .tenantId("tenant-1")
                    .roles(roles)
                    .build());
            OpenApiTaskDTO result = facade.terminate(
                    managementRequest(), "agent-1", "task-1", REQUEST_ID);
            assertEquals("CANCEL_REQUESTED", result.getStatus());
        }
        verify(management, times(3)).terminate("agent-1", "task-1", REQUEST_ID);
        verifyNoInteractions(runtime);

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("viewer")
                .tenantId("tenant-1")
                .roles("VIEWER")
                .build());
        assertThrows(SecurityException.class,
                () -> facade.terminate(
                        managementRequest(), "agent-1", "task-1", REQUEST_ID));
        verify(management, times(3)).terminate("agent-1", "task-1", REQUEST_ID);
    }

    @Test
    void managementAuthAndRoleRunBeforeMalformedRequestIdValidation() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);

        SecurityException unauthenticated = assertThrows(
                SecurityException.class,
                () -> facade.terminate(
                        managementRequest(), "agent-1", "task-1", "not-a-uuid"));
        assertEquals("未登录，请先登录", unauthenticated.getMessage());

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("viewer")
                .tenantId("tenant-1")
                .roles("VIEWER")
                .build());
        SecurityException forbidden = assertThrows(
                SecurityException.class,
                () -> facade.terminate(
                        managementRequest(), "agent-1", "task-1", "not-a-uuid"));
        assertEquals("无权限访问此接口", forbidden.getMessage());

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("developer")
                .tenantId("tenant-1")
                .roles("DEVELOPER")
                .build());
        IllegalArgumentException malformed = assertThrows(
                IllegalArgumentException.class,
                () -> facade.terminate(
                        managementRequest(), "agent-1", "task-1", "not-a-uuid"));
        assertEquals("OPEN_API_AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID",
                malformed.getMessage());

        verifyNoInteractions(runtime, management);
    }

    @Test
    void downstreamSecurityTextCannotMasqueradeAsPublicStableCode() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        when(management.terminate("agent-1", "task-1", REQUEST_ID))
                .thenThrow(new SecurityException(
                        "RUNTIME_SECRET_TOKEN leaked-provider-detail"));
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("developer")
                .tenantId("tenant-1")
                .roles("DEVELOPER")
                .build());

        SecurityException sanitized = assertThrows(
                SecurityException.class,
                () -> facade.terminate(
                        managementRequest(), "agent-1", "task-1", REQUEST_ID));

        assertEquals("OPEN_API_AGENT_CANCEL_FAILED", sanitized.getMessage());
        verifyNoInteractions(runtime);
    }

    @Test
    void missingRejectedOrDriftedCensusFailsBeforeEitherAdapter() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);

        MockHttpServletRequest missing = cancelRequest();
        missing.addHeader("X-API-Key", "api-key");
        assertEquals(OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_MISSING,
                assertThrows(SecurityException.class,
                        () -> facade.terminate(
                                missing, "agent-1", "task-1", REQUEST_ID))
                        .getMessage());

        MockHttpServletRequest rejected = runtimeRequest();
        rejected.addHeader("Authorization", "Bearer jwt");
        storeDecision(rejected);
        assertEquals(OpenApiAgentCancelCredentialCensus.CREDENTIAL_MIXED,
                assertThrows(SecurityException.class,
                        () -> facade.terminate(
                                rejected, "agent-1", "task-1", REQUEST_ID))
                        .getMessage());

        MockHttpServletRequest required = cancelRequest();
        storeDecision(required);
        assertEquals("未登录，请先登录",
                assertThrows(SecurityException.class,
                        () -> facade.terminate(
                                required, "agent-1", "task-1", REQUEST_ID))
                        .getMessage());

        MockHttpServletRequest drifted = runtimeRequest();
        storeDecision(drifted);
        drifted.addHeader("X-API-Key", "late-management-key");
        assertEquals(OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_DRIFT,
                assertThrows(SecurityException.class,
                        () -> facade.terminate(
                                drifted, "agent-1", "task-1", REQUEST_ID))
                        .getMessage());
        verifyNoInteractions(runtime, management);
    }

    @Test
    void invalidRequestIdAndRuntimeIdentityDriftFailClosed() {
        RuntimeTaskClosureService runtime = mock(RuntimeTaskClosureService.class);
        ScopedOpenApiManagementTaskTerminationCommandAdapter management =
                mock(ScopedOpenApiManagementTaskTerminationCommandAdapter.class);
        OpenApiAgentTaskTerminationFacade facade = facade(runtime, management);
        MockHttpServletRequest request = runtimeRequest();

        assertEquals("OPEN_API_AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID",
                assertThrows(IllegalArgumentException.class,
                        () -> facade.terminate(
                                request, "agent-1", "task-1", "not-a-uuid"))
                        .getMessage());
        verify(runtime, never()).terminateAgentTaskWithRuntimeAccess(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());

        when(runtime.terminateAgentTaskWithRuntimeAccess(
                "app-key", "access-token", "upstream-user", REQUEST_ID,
                "agent-1", "task-1"))
                .thenReturn(new RuntimeTaskClosureService.AgentTerminationResult(
                        REQUEST_ID, "other-task", "agent-1", false, false, null));
        assertEquals("OPEN_API_AGENT_CANCEL_RESULT_IDENTITY_CONFLICT",
                assertThrows(IllegalStateException.class,
                        () -> facade.terminate(
                                request, "agent-1", "task-1", REQUEST_ID))
                        .getMessage());
        verifyNoInteractions(management);
    }

    private static OpenApiAgentTaskTerminationFacade facade(
            RuntimeTaskClosureService runtime,
            ScopedOpenApiManagementTaskTerminationCommandAdapter management) {
        OpenApiManagementTaskTerminationRoleGate target =
                new OpenApiManagementTaskTerminationRoleGate(management);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new AuthAspect());
        OpenApiManagementTaskTerminationRoleGate secured = proxyFactory.getProxy();
        return new OpenApiAgentTaskTerminationFacade(runtime, secured);
    }

    private static MockHttpServletRequest managementRequest() {
        MockHttpServletRequest request = cancelRequest();
        request.addHeader("Authorization", "Bearer management-token");
        storeDecision(request);
        return request;
    }

    private static MockHttpServletRequest runtimeRequest() {
        MockHttpServletRequest request = cancelRequest();
        request.addHeader("X-Client-App-Key", "app-key");
        request.addHeader("X-Client-App-Access-Token", "access-token");
        request.addHeader("X-Upstream-User-Id", "upstream-user");
        storeDecision(request);
        return request;
    }

    private static MockHttpServletRequest cancelRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/open/agents/agent-1/tasks/task-1/cancel");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                OpenApiAgentCancelCredentialCensus.ROUTE_PATTERN);
        return request;
    }

    private static void storeDecision(MockHttpServletRequest request) {
        OpenApiAgentCancelCredentialCensus.Decision decision =
                OpenApiAgentCancelCredentialCensus.inspect(request);
        assertTrue(decision != null);
        OpenApiAgentCancelCredentialCensus.store(request, decision);
    }
}

package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopedOpenApiManagementTaskTerminationCommandAdapterTest {

    private static final String CALLER_ID = "management-caller";
    private static final String OWNER_ID = "durable-owner";
    private static final String TENANT_ID = "tenant-1";
    private static final String ROUTE =
            "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel";
    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private TaskTerminationCommandCoordinator commandCoordinator;
    @Mock
    private SessionTaskResourceAccessService resources;

    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private ScopedOpenApiManagementTaskTerminationCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.management.termination.v1",
                Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        adapter = new ScopedOpenApiManagementTaskTerminationCommandAdapter(
                taskDispatchFacade,
                commandCoordinator,
                serverAuthority,
                new TrustedNavigatorCommandIngressAuthority(),
                resources);
    }

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void managementActorAndDurableTaskOwnerRemainDistinct() {
        bindApiKey();
        SessionTaskResourceAccessService.ManagedTaskIdentity managed = managed();
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan = plan();
        when(resources.requireTenantTask("task-1", TENANT_ID)).thenReturn(managed);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed(
                        TaskTerminationCommandCoordinator.Outcome.accepted()));

        ScopedOpenApiManagementTaskTerminationCommandAdapter.TerminationResult result =
                adapter.terminate("agent-1", "task-1", REQUEST_ID);

        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                result.safeCode());
        assertNull(result.terminalStatus());
        assertEquals("TerminationResult[safe]", result.toString());
        verify(taskDispatchFacade).resolveTerminationExecutionPlan(
                eq("task-1"),
                argThat(context -> OWNER_ID.equals(context.getUserId())
                        && TENANT_ID.equals(context.getTenantId())
                        && "session-1".equals(context.getSessionId())
                        && "OPEN_API".equals(context.getRequestSource())),
                eq(false));

        CapturedCommand captured = capture(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                serverAuthority.requireVerified(captured.envelope(), captured.decision());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_OPEN_API", binding.ingress().clientSurface());
        assertEquals(ROUTE, binding.ingress().routeId());
        assertEquals(AuthorizationPrincipalType.NAVIGATOR_USER,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                binding.actor().lane());
        assertEquals(OWNER_ID, binding.ownership().ownerReference());
        assertEquals(TaskTerminationCommandCoordinator.canonicalTenantReference(TENANT_ID),
                binding.ownership().tenantReference());
        assertNull(binding.ownership().clientAppReference());
        assertNull(binding.ownership().upstreamReference());
        assertFalse(captured.envelope().toString().contains("raw-api-key-secret"));
        assertFalse(captured.envelope().toString().contains(CALLER_ID));
    }

    @Test
    void explicitQueryTokenRequestIdIsCanonicalAndReplaySafe() {
        bindQueryToken();
        when(resources.requireTenantTask("task-1", TENANT_ID)).thenReturn(managed());
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan = plan();
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(
                        executed(TaskTerminationCommandCoordinator.Outcome.accepted()),
                        replay(TaskTerminationCommandCoordinator.Outcome.accepted()));

        var first = adapter.terminate(
                "agent-1", "task-1",
                " 550E8400-E29B-41D4-A716-446655440000 ");
        var second = adapter.terminate("agent-1", "task-1", REQUEST_ID);

        assertEquals(first, second);
        ArgumentCaptor<CanonicalCommandEnvelope> envelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                eq(plan), envelopes.capture(), any());
        for (CanonicalCommandEnvelope envelope : envelopes.getAllValues()) {
            assertEquals(REQUEST_ID,
                    envelope.binding().request().clientRequestId());
            assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                    envelope.binding().actor().lane());
        }
    }

    @Test
    void absentAndBlankRequestIdsMintDistinctCommands() {
        bindBearer();
        when(resources.requireTenantTask("task-1", TENANT_ID)).thenReturn(managed());
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan = plan();
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed(
                        TaskTerminationCommandCoordinator.Outcome.accepted()));

        adapter.terminate("agent-1", "task-1", null);
        adapter.terminate("agent-1", "task-1", "  ");

        ArgumentCaptor<CanonicalCommandEnvelope> envelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                eq(plan), envelopes.capture(), any());
        String absent = envelopes.getAllValues().get(0)
                .binding().request().clientRequestId();
        String blank = envelopes.getAllValues().get(1)
                .binding().request().clientRequestId();
        assertEquals(UUID.fromString(absent).toString(), absent);
        assertEquals(UUID.fromString(blank).toString(), blank);
        assertNotEquals(absent, blank);
    }

    @Test
    void tenantResourcePathAgentAndPlanDriftFailBeforeReceipt() {
        bindBearer();
        when(resources.requireTenantTask("task-cross", TENANT_ID))
                .thenThrow(new SecurityException("Resource access denied"));
        assertEquals("Resource access denied",
                assertThrows(SecurityException.class,
                        () -> adapter.terminate(
                                "agent-1", "task-cross", REQUEST_ID))
                        .getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);

        when(resources.requireTenantTask("task-1", TENANT_ID)).thenReturn(managed());
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan = plan();
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        assertEquals("Resource access denied",
                assertThrows(SecurityException.class,
                        () -> adapter.terminate(
                                "agent-other", "task-1", REQUEST_ID))
                        .getMessage());
        verifyNoInteractions(commandCoordinator);

        org.mockito.Mockito.reset(taskDispatchFacade, commandCoordinator);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false)))
                .thenReturn(plan("owner-drift", TENANT_ID, "session-1", "agent-1"));
        assertEquals(
                "TRUSTED_NAVIGATOR_OPEN_API_TERMINATION_PLAN_OWNER_CONFLICT",
                assertThrows(SecurityException.class,
                        () -> adapter.terminate(
                                "agent-1", "task-1", REQUEST_ID))
                        .getMessage());
        verifyNoInteractions(commandCoordinator);
    }

    private CapturedCommand capture(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        ArgumentCaptor<CanonicalCommandEnvelope> envelope =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decision =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(
                eq(plan), envelope.capture(), decision.capture());
        return new CapturedCommand(envelope.getValue(), decision.getValue());
    }

    private static SessionTaskResourceAccessService.ManagedTaskIdentity managed() {
        return new SessionTaskResourceAccessService.ManagedTaskIdentity(
                "task-1", "session-1", OWNER_ID, TENANT_ID, "agent-1");
    }

    private static TaskTerminationCommandCoordinator.TerminationExecutionPlan plan() {
        return plan(OWNER_ID, TENANT_ID, "session-1", "agent-1");
    }

    private static TaskTerminationCommandCoordinator.TerminationExecutionPlan plan(
            String owner,
            String tenant,
            String session,
            String agent) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity =
                new TaskTerminationCommandCoordinator.TerminationIdentity(
                        "task-1",
                        owner,
                        tenant,
                        session,
                        "provider-task-1",
                        agent,
                        "codex-worker",
                        "worker-1",
                        "directory-1",
                        "gpt-5.4",
                        "model-config-1",
                        "runtime-1",
                        2,
                        "CODEX",
                        "runtime-instance-1",
                        3L,
                        TaskTerminationCommandCoordinator.ExecutionRoute.PROVIDER,
                        false);
        return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                identity,
                AgentResolveContext.builder()
                        .userId(owner)
                        .tenantId(tenant)
                        .sessionId(session)
                        .requestSource("OPEN_API")
                        .build(),
                null,
                new TaskTerminationCommandCoordinator.CapturedTerminationEffect(
                        TaskTerminationCommandCoordinator.Outcome::accepted));
    }

    private static TaskTerminationCommandCoordinator.Executed executed(
            TaskTerminationCommandCoordinator.Outcome outcome) {
        return new TaskTerminationCommandCoordinator.Executed(
                new TaskTerminationCommandCoordinator.TaskReference("task-1"), outcome);
    }

    private static TaskTerminationCommandCoordinator.RecordedReplay replay(
            TaskTerminationCommandCoordinator.Outcome outcome) {
        return new TaskTerminationCommandCoordinator.RecordedReplay(
                new TaskTerminationCommandCoordinator.TaskReference("task-1"), outcome);
    }

    private MockHttpServletRequest bindBearer() {
        MockHttpServletRequest request = bind();
        request.addHeader("Authorization", "Bearer raw-jwt-secret");
        return request;
    }

    private MockHttpServletRequest bindQueryToken() {
        MockHttpServletRequest request = bind();
        request.addParameter("token", "raw-query-secret");
        return request;
    }

    private MockHttpServletRequest bindApiKey() {
        MockHttpServletRequest request = bind();
        request.addHeader("X-API-Key", "raw-api-key-secret");
        return request;
    }

    private MockHttpServletRequest bind() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
        request.setAttribute("userId", CALLER_ID);
        request.setAttribute("username", "manager");
        request.setAttribute("tenantId", TENANT_ID);
        request.setAttribute("roles", "DEVELOPER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(CALLER_ID)
                .username("manager")
                .tenantId(TENANT_ID)
                .roles("DEVELOPER")
                .build());
        return request;
    }

    private record CapturedCommand(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

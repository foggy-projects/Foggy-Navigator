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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustedNavigatorTaskTerminationCommandAdapterTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String USERNAME = "foggy";
    private static final String ROLES = "USER";
    private static final String TASK_ROUTE = "/api/v1/tasks/{taskId}/cancel";
    private static final String A2A_ROUTE =
            "/api/v1/agents/{agentId}/tasks/{taskId}/cancel";
    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private TaskTerminationCommandCoordinator commandCoordinator;

    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private TrustedNavigatorTaskTerminationCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.termination.policy.v1",
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        adapter = new TrustedNavigatorTaskTerminationCommandAdapter(
                taskDispatchFacade,
                commandCoordinator,
                serverAuthority,
                new TrustedNavigatorCommandIngressAuthority());
    }

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void uiTerminationBindsExactTrustedIngressPlanAndForce() {
        bindBearer(TASK_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", USER_ID, TENANT_ID, "agent-1", true, "UI", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(true))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed("task-1",
                        TaskTerminationCommandCoordinator.Outcome.accepted()));

        TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult result =
                adapter.terminateUiTask(
                        "task-1", true,
                        " 550E8400-E29B-41D4-A716-446655440000 ");

        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                result.safeCode());
        assertNull(result.terminalStatus());
        assertEquals("TerminationResult[safe]", result.toString());
        verify(taskDispatchFacade).resolveTerminationExecutionPlan(
                eq("task-1"),
                argThat(context -> USER_ID.equals(context.getUserId())
                        && TENANT_ID.equals(context.getTenantId())
                        && "UI".equals(context.getRequestSource())),
                eq(true));

        CapturedCommand captured = capture(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                serverAuthority.requireVerified(captured.envelope(), captured.decision());
        assertEquals(CanonicalCommandEnvelope.CommandKind.TERMINATE,
                binding.commandKind());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_UI", binding.ingress().clientSurface());
        assertEquals(TASK_ROUTE, binding.ingress().routeId());
        assertEquals(REQUEST_ID, binding.request().clientRequestId());
        assertEquals(REQUEST_ID, binding.request().idempotencyKey());
        assertEquals(REQUEST_ID, binding.request().correlationId());
        assertEquals(AuthorizationPrincipalType.NAVIGATOR_USER,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                binding.actor().lane());
        assertEquals(64, binding.actor().fingerprint().length());
        assertFalse(captured.envelope().toString().contains("raw-jwt-secret"));
        assertEquals(USER_ID, binding.ownership().ownerReference());
        TaskTerminationCommandCoordinator.PlanBinding expected =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        assertEquals(expected.tenantReference(),
                binding.ownership().tenantReference());
        assertEquals(expected.target(), binding.target());
        assertEquals(expected.effect(), binding.effect());
    }

    @Test
    void a2aTerminationUsesCanonicalA2aSourceAndApiKeyLane() {
        bindApiKey(A2A_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", USER_ID, TENANT_ID, "agent-1", false, "A2A", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed("task-1",
                        TaskTerminationCommandCoordinator.Outcome.accepted()));

        adapter.terminateA2aTask("agent-1", "task-1", REQUEST_ID);

        verify(taskDispatchFacade).resolveTerminationExecutionPlan(
                eq("task-1"),
                argThat(context -> USER_ID.equals(context.getUserId())
                        && TENANT_ID.equals(context.getTenantId())
                        && "A2A".equals(context.getRequestSource())),
                eq(false));
        CapturedCommand captured = capture(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                serverAuthority.requireVerified(captured.envelope(), captured.decision());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.A2A,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_A2A", binding.ingress().clientSurface());
        assertEquals(A2A_ROUTE, binding.ingress().routeId());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                binding.actor().lane());
        assertEquals("agent-1", binding.target().logicalAgentId());
    }

    @Test
    void absentAndBlankRequestIdsMintDistinctCanonicalCommands() {
        bindBearer(TASK_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", USER_ID, TENANT_ID, "agent-1", false, "UI", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed("task-1",
                        TaskTerminationCommandCoordinator.Outcome.accepted()));

        adapter.terminateUiTask("task-1", false, null);
        adapter.terminateUiTask("task-1", false, "  ");

        ArgumentCaptor<CanonicalCommandEnvelope> envelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                eq(plan), envelopes.capture(), any());
        List<CanonicalCommandEnvelope> values = envelopes.getAllValues();
        String absent = values.get(0).binding().request().clientRequestId();
        String blank = values.get(1).binding().request().clientRequestId();
        assertEquals(UUID.fromString(absent).toString(), absent);
        assertEquals(UUID.fromString(blank).toString(), blank);
        assertNotEquals(absent, blank);
        assertEquals(absent, values.get(0).binding().request().idempotencyKey());
        assertEquals(blank, values.get(1).binding().request().correlationId());
    }

    @Test
    void invalidRequestIdAndWrongRouteFailBeforePlanOrReceipt() {
        bindBearer(TASK_ROUTE);

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> adapter.terminateUiTask("task-1", false, "not-a-uuid"));
        assertEquals("clientRequestId must be a canonical UUID", invalid.getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);

        bindBearer(A2A_ROUTE);
        SecurityException route = assertThrows(SecurityException.class,
                () -> adapter.terminateUiTask("task-1", false, REQUEST_ID));
        assertEquals("TRUSTED_NAVIGATOR_TERMINATION_ROUTE_SOURCE_CONFLICT",
                route.getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);
    }

    @Test
    void pathAgentAndPlanIdentityDriftFailBeforeReceipt() {
        bindBearer(A2A_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", USER_ID, TENANT_ID, "agent-owner", false, "A2A", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);

        SecurityException pathDrift = assertThrows(SecurityException.class,
                () -> adapter.terminateA2aTask(
                        "agent-other", "task-1", REQUEST_ID));
        assertEquals("Resource access denied", pathDrift.getMessage());
        verifyNoInteractions(commandCoordinator);

        SecurityException blankPath = assertThrows(SecurityException.class,
                () -> adapter.terminateA2aTask(" ", "task-1", REQUEST_ID));
        assertEquals("Resource access denied", blankPath.getMessage());
        verifyNoInteractions(commandCoordinator);

        org.mockito.Mockito.reset(taskDispatchFacade, commandCoordinator);
        bindBearer(TASK_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan ownerDrift =
                plan("task-1", "user-other", TENANT_ID,
                        "agent-owner", false, "UI", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(ownerDrift);

        SecurityException owner = assertThrows(SecurityException.class,
                () -> adapter.terminateUiTask("task-1", false, REQUEST_ID));
        assertEquals("TRUSTED_NAVIGATOR_TERMINATION_PLAN_OWNER_CONFLICT",
                owner.getMessage());
        verifyNoInteractions(commandCoordinator);

        org.mockito.Mockito.reset(taskDispatchFacade, commandCoordinator);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan tenantDrift =
                plan("task-1", USER_ID, "tenant-other",
                        "agent-owner", false, "UI", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(tenantDrift);

        SecurityException tenant = assertThrows(SecurityException.class,
                () -> adapter.terminateUiTask("task-1", false, REQUEST_ID));
        assertEquals("TRUSTED_NAVIGATOR_TERMINATION_PLAN_OWNER_CONFLICT",
                tenant.getMessage());
        verifyNoInteractions(commandCoordinator);

        org.mockito.Mockito.reset(taskDispatchFacade, commandCoordinator);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan taskDrift =
                plan("task-other", USER_ID, TENANT_ID,
                        "agent-owner", false, "UI", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(taskDrift);

        IllegalStateException task = assertThrows(IllegalStateException.class,
                () -> adapter.terminateUiTask("task-1", false, REQUEST_ID));
        assertEquals("TERMINATION_PLAN_TASK_CONFLICT", task.getMessage());
        verifyNoInteractions(commandCoordinator);
    }

    @Test
    void unsupportedPlanAdmissionNeverReachesReceipt() {
        bindBearer(TASK_ROUTE);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(true)))
                .thenThrow(new UnsupportedOperationException(
                        "TERMINATION_REQUEST_NOT_SUPPORTED"));

        UnsupportedOperationException unsupported = assertThrows(
                UnsupportedOperationException.class,
                () -> adapter.terminateUiTask("task-1", true, REQUEST_ID));

        assertEquals("TERMINATION_REQUEST_NOT_SUPPORTED", unsupported.getMessage());
        verifyNoInteractions(commandCoordinator);
    }

    @Test
    void freshReplayAndTerminalMapToStableResultsWithoutReplayDisclosure() {
        bindBearer(TASK_ROUTE);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan active =
                plan("task-1", USER_ID, TENANT_ID, "agent-1", false, "UI", null);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan terminal =
                plan("task-1", USER_ID, TENANT_ID,
                        "agent-1", false, "UI", "ABORTED");
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false)))
                .thenReturn(active, active, terminal);
        when(commandCoordinator.execute(eq(active), any(), any()))
                .thenReturn(
                        executed("task-1",
                                TaskTerminationCommandCoordinator.Outcome.accepted()),
                        replay("task-1",
                                TaskTerminationCommandCoordinator.Outcome.accepted()));
        when(commandCoordinator.execute(eq(terminal), any(), any()))
                .thenReturn(executed(
                        "task-1",
                        TaskTerminationCommandCoordinator.Outcome.alreadyTerminal("ABORTED")));

        TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult fresh =
                adapter.terminateUiTask("task-1", false, REQUEST_ID);
        TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult replay =
                adapter.terminateUiTask("task-1", false, REQUEST_ID);
        TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult alreadyTerminal =
                adapter.terminateUiTask("task-1", false, REQUEST_ID);

        assertEquals(fresh, replay);
        assertEquals("TASK_ALREADY_TERMINAL_ABORTED", alreadyTerminal.safeCode());
        assertEquals("ABORTED", alreadyTerminal.terminalStatus());
        assertTrue(alreadyTerminal.toString().contains("safe"));
        assertFalse(alreadyTerminal.toString().contains("task-1"));
        verify(taskDispatchFacade, times(3)).resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false));
    }

    private CapturedCommand capture(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        ArgumentCaptor<CanonicalCommandEnvelope> envelope =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decision =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(eq(plan), envelope.capture(), decision.capture());
        return new CapturedCommand(envelope.getValue(), decision.getValue());
    }

    private TaskTerminationCommandCoordinator.TerminationExecutionPlan plan(
            String taskId,
            String ownerUserId,
            String tenantId,
            String logicalAgentId,
            boolean force,
            String requestSource,
            String terminalStatus) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity =
                new TaskTerminationCommandCoordinator.TerminationIdentity(
                        taskId,
                        ownerUserId,
                        tenantId,
                        "session-1",
                        "provider-task-1",
                        logicalAgentId,
                        "codex-worker",
                        "worker-1",
                        "directory-1",
                        "gpt-5.4",
                        "model-config-1",
                        "runtime-1",
                        2,
                        "CODEX",
                        "instance-1",
                        3L,
                        TaskTerminationCommandCoordinator.ExecutionRoute.PROVIDER,
                        force);
        return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                identity,
                AgentResolveContext.builder()
                        .userId(ownerUserId)
                        .tenantId(tenantId)
                        .requestSource(requestSource)
                        .build(),
                terminalStatus,
                terminalStatus == null
                        ? new TaskTerminationCommandCoordinator.CapturedTerminationEffect(
                        TaskTerminationCommandCoordinator.Outcome::accepted)
                        : null);
    }

    private static TaskTerminationCommandCoordinator.Executed executed(
            String taskId,
            TaskTerminationCommandCoordinator.Outcome outcome) {
        return new TaskTerminationCommandCoordinator.Executed(
                new TaskTerminationCommandCoordinator.TaskReference(taskId), outcome);
    }

    private static TaskTerminationCommandCoordinator.RecordedReplay replay(
            String taskId,
            TaskTerminationCommandCoordinator.Outcome outcome) {
        return new TaskTerminationCommandCoordinator.RecordedReplay(
                new TaskTerminationCommandCoordinator.TaskReference(taskId), outcome);
    }

    private MockHttpServletRequest bindBearer(String route) {
        MockHttpServletRequest request = bind(route);
        request.addHeader("Authorization", "Bearer raw-jwt-secret");
        return request;
    }

    private MockHttpServletRequest bindApiKey(String route) {
        MockHttpServletRequest request = bind(route);
        request.addHeader("X-API-Key", "raw-api-key-secret");
        return request;
    }

    private MockHttpServletRequest bind(String route) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", route);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.setAttribute("userId", USER_ID);
        request.setAttribute("username", USERNAME);
        request.setAttribute("tenantId", TENANT_ID);
        request.setAttribute("roles", ROLES);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .username(USERNAME)
                .tenantId(TENANT_ID)
                .roles(ROLES)
                .build());
        return request;
    }

    private record CapturedCommand(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

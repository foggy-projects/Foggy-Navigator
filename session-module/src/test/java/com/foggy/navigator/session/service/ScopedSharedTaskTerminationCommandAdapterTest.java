package com.foggy.navigator.session.service;

import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SharingKeyRepository;
import com.foggy.navigator.session.util.SharingKeyGenerator;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopedSharedTaskTerminationCommandAdapterTest {

    private static final String RAW_KEY = "shk-full-secret";
    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";
    private static final String OWNER_ID = "owner-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String AGENT_ID = "agent-1";

    @Mock private SharingKeyRepository sharingKeyRepository;
    @Mock private SharingKeyGenerator sharingKeyGenerator;
    @Mock private UnifiedAgentResolver agentResolver;
    @Mock private UserRepository userRepository;
    @Mock private TaskDispatchFacade taskDispatchFacade;
    @Mock private TaskTerminationCommandCoordinator commandCoordinator;

    private SharingKeyService sharingKeyService;
    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private ScopedSharedTaskTerminationCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        sharingKeyService = new SharingKeyService(
                sharingKeyRepository,
                sharingKeyGenerator,
                agentResolver,
                userRepository,
                "http://localhost:8112");
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.shared.termination.policy.v1",
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        adapter = new ScopedSharedTaskTerminationCommandAdapter(
                sharingKeyService,
                taskDispatchFacade,
                commandCoordinator,
                serverAuthority);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void sharedTerminationBindsContentFreeAuthorityPlanAndEnvelope() {
        stubCurrentAuthority(key(AGENT_ID, OWNER_ID));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", OWNER_ID, TENANT_ID, AGENT_ID, null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed(
                        "task-1", TaskTerminationCommandCoordinator.Outcome.accepted()));
        CurrentUser ambient = CurrentUser.builder()
                .userId("jwt-attacker")
                .tenantId("foreign-tenant")
                .build();
        UserContext.setCurrentUser(ambient);

        ScopedSharedTaskTerminationCommandAdapter.TerminationResult result =
                adapter.terminateTask(
                        RAW_KEY,
                        "task-1",
                        " 550E8400-E29B-41D4-A716-446655440000 ");

        assertEquals("TERMINATION_REQUEST_ACCEPTED", result.safeCode());
        assertNull(result.terminalStatus());
        assertEquals("SharedTerminationResult[safe]", result.toString());
        assertSame(ambient, UserContext.getCurrentUser());
        verify(taskDispatchFacade).resolveTerminationExecutionPlan(
                eq("task-1"),
                org.mockito.ArgumentMatchers.argThat(context ->
                        OWNER_ID.equals(context.getUserId())
                                && TENANT_ID.equals(context.getTenantId())
                                && "SHARED_API".equals(context.getRequestSource())),
                eq(false));

        CapturedCommand captured = capture(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                serverAuthority.requireVerified(captured.envelope(), captured.decision());
        assertEquals(CanonicalCommandEnvelope.CommandKind.TERMINATE,
                binding.commandKind());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.SHARED,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_SHARED_API", binding.ingress().clientSurface());
        assertEquals("/api/v1/shared/tasks/{taskId}/cancel",
                binding.ingress().routeId());
        assertEquals(REQUEST_ID, binding.request().clientRequestId());
        assertEquals(REQUEST_ID, binding.request().idempotencyKey());
        assertEquals(REQUEST_ID, binding.request().correlationId());
        assertEquals(AuthorizationPrincipalType.SHARE_GRANTEE,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.SHARING_KEY_CAPABILITY,
                binding.actor().lane());
        assertEquals(64, binding.actor().fingerprint().length());
        assertEquals(OWNER_ID, binding.ownership().ownerReference());
        TaskTerminationCommandCoordinator.PlanBinding expected =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        assertEquals(expected.tenantReference(),
                binding.ownership().tenantReference());
        assertEquals(expected.target(), binding.target());
        assertEquals(expected.effect(), binding.effect());
        assertFalse(captured.envelope().toString().contains(RAW_KEY));
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());

        InOrder ordered = inOrder(
                sharingKeyRepository, taskDispatchFacade, commandCoordinator);
        ordered.verify(sharingKeyRepository).findBySharingKey(RAW_KEY);
        ordered.verify(taskDispatchFacade).resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false));
        ordered.verify(sharingKeyRepository).findById("key-1");
        ordered.verify(commandCoordinator).execute(eq(plan), any(), any());
    }

    @Test
    void absentAndBlankRequestIdsMintDistinctCanonicalCommands() {
        stubCurrentAuthority(key(AGENT_ID, OWNER_ID));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", OWNER_ID, TENANT_ID, AGENT_ID, null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        when(commandCoordinator.execute(eq(plan), any(), any()))
                .thenReturn(executed(
                        "task-1", TaskTerminationCommandCoordinator.Outcome.accepted()));

        adapter.terminateTask(RAW_KEY, "task-1", null);
        adapter.terminateTask(RAW_KEY, "task-1", "  ");

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
    }

    @Test
    void invalidKeyAndMalformedRequestIdFailBeforePlanOrCoordinator() {
        when(sharingKeyRepository.findBySharingKey("invalid"))
                .thenReturn(Optional.empty());

        ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException invalid = assertThrows(
                ScopedSharedTaskTerminationCommandAdapter
                        .SharedTerminationAdmissionRejectedException.class,
                () -> adapter.terminateTask("invalid", "task-1", REQUEST_ID));
        assertEquals("Invalid sharing key", invalid.getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);

        reset(sharingKeyRepository, userRepository);
        stubMintAuthority(key(AGENT_ID, OWNER_ID));
        ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException malformed = assertThrows(
                ScopedSharedTaskTerminationCommandAdapter
                        .SharedTerminationAdmissionRejectedException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", "not-a-uuid"));
        assertEquals("clientRequestId must be a canonical UUID",
                malformed.getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);
        verify(sharingKeyRepository, never()).findById(anyString());
    }

    @Test
    void planIdentityDriftFailsBeforeAuthorityRevalidationOrCoordinator() {
        stubMintAuthority(key(AGENT_ID, OWNER_ID));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan agentDrift =
                plan("task-1", OWNER_ID, TENANT_ID, "agent-other", null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(agentDrift);

        ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException agent = assertThrows(
                ScopedSharedTaskTerminationCommandAdapter
                        .SharedTerminationAdmissionRejectedException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("Task not found: task-1", agent.getMessage());
        verify(sharingKeyRepository, never()).findById(anyString());
        verifyNoInteractions(commandCoordinator);

        clearInvocations(sharingKeyRepository, userRepository);
        reset(taskDispatchFacade, commandCoordinator);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan taskDrift =
                plan("task-other", OWNER_ID, TENANT_ID, AGENT_ID, null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(taskDrift);
        ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException task = assertThrows(
                ScopedSharedTaskTerminationCommandAdapter
                        .SharedTerminationAdmissionRejectedException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("Task not found: task-1", task.getMessage());
        verify(sharingKeyRepository, never()).findById(anyString());
        verifyNoInteractions(commandCoordinator);

        clearInvocations(sharingKeyRepository, userRepository);
        reset(taskDispatchFacade, commandCoordinator);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan ownerDrift =
                plan("task-1", "owner-other", TENANT_ID, AGENT_ID, null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(ownerDrift);
        SecurityException owner = assertThrows(SecurityException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("shared resource is not accessible", owner.getMessage());
        verify(sharingKeyRepository, never()).findById(anyString());
        verifyNoInteractions(commandCoordinator);
    }

    @Test
    void currentAuthorityDriftIsReadOnlyAndRejectedBeforeCoordinator() {
        SharingKeyEntity initial = key(AGENT_ID, OWNER_ID);
        SharingKeyEntity disabled = key(AGENT_ID, OWNER_ID);
        disabled.setEnabled(false);
        stubMintAuthority(initial);
        when(sharingKeyRepository.findById("key-1"))
                .thenReturn(Optional.of(disabled));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                plan("task-1", OWNER_ID, TENANT_ID, AGENT_ID, null);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);

        ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException revoked = assertThrows(
                ScopedSharedTaskTerminationCommandAdapter
                        .SharedTerminationAdmissionRejectedException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("Sharing key is disabled", revoked.getMessage());
        verifyNoInteractions(commandCoordinator);
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());

        reset(sharingKeyRepository, userRepository, taskDispatchFacade, commandCoordinator);
        stubMintAuthority(initial);
        SharingKeyEntity ownerDrift = key(AGENT_ID, "owner-other");
        when(sharingKeyRepository.findById("key-1"))
                .thenReturn(Optional.of(ownerDrift));
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false))).thenReturn(plan);
        SecurityException owner = assertThrows(SecurityException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("shared resource is not accessible", owner.getMessage());
        verifyNoInteractions(commandCoordinator);
        verify(sharingKeyRepository, never()).save(any());
    }

    @Test
    void foreignAuthorityAndUnsupportedPlanNeverReachReceipt() {
        stubMintAuthority(key(AGENT_ID, OWNER_ID));
        SharingKeyService.SharedTaskTerminationAuthority authority =
                sharingKeyService.mintTaskTerminationAuthority(RAW_KEY);
        SharingKeyRepository foreignRepository = mock(SharingKeyRepository.class);
        SharingKeyService foreignService = new SharingKeyService(
                foreignRepository,
                mock(SharingKeyGenerator.class),
                mock(UnifiedAgentResolver.class),
                mock(UserRepository.class),
                "http://localhost:8112");

        SecurityException foreign = assertThrows(SecurityException.class,
                () -> foreignService.requireCurrentTaskTerminationAuthority(authority));
        assertEquals("shared resource is not accessible", foreign.getMessage());
        assertEquals("SharedTaskTerminationAuthority[content-free]",
                authority.toString());
        assertFalse(authority.toString().contains(RAW_KEY));
        verifyNoInteractions(foreignRepository);

        clearInvocations(sharingKeyRepository, userRepository);
        when(taskDispatchFacade.resolveTerminationExecutionPlan(
                eq("task-1"), any(), eq(false)))
                .thenThrow(new UnsupportedOperationException(
                        "TERMINATION_REQUEST_NOT_SUPPORTED"));
        UnsupportedOperationException unsupported = assertThrows(
                UnsupportedOperationException.class,
                () -> adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID));
        assertEquals("TERMINATION_REQUEST_NOT_SUPPORTED", unsupported.getMessage());
        verify(sharingKeyRepository, never()).findById(anyString());
        verifyNoInteractions(commandCoordinator);
    }

    @Test
    void freshReplayAndTerminalReturnStableSafeResults() {
        stubCurrentAuthority(key(AGENT_ID, OWNER_ID));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan active =
                plan("task-1", OWNER_ID, TENANT_ID, AGENT_ID, null);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan terminal =
                plan("task-1", OWNER_ID, TENANT_ID, AGENT_ID, "ABORTED");
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
                        TaskTerminationCommandCoordinator.Outcome
                                .alreadyTerminal("ABORTED")));

        ScopedSharedTaskTerminationCommandAdapter.TerminationResult fresh =
                adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID);
        ScopedSharedTaskTerminationCommandAdapter.TerminationResult replay =
                adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID);
        ScopedSharedTaskTerminationCommandAdapter.TerminationResult terminalResult =
                adapter.terminateTask(RAW_KEY, "task-1", REQUEST_ID);

        assertEquals(fresh, replay);
        assertEquals("TASK_ALREADY_TERMINAL_ABORTED", terminalResult.safeCode());
        assertEquals("ABORTED", terminalResult.terminalStatus());
        assertFalse(terminalResult.toString().contains("task-1"));
    }

    private void stubMintAuthority(SharingKeyEntity entity) {
        when(sharingKeyRepository.findBySharingKey(RAW_KEY))
                .thenReturn(Optional.of(entity));
        when(userRepository.findById(OWNER_ID))
                .thenReturn(Optional.of(owner(OWNER_ID, TENANT_ID)));
    }

    private void stubCurrentAuthority(SharingKeyEntity entity) {
        stubMintAuthority(entity);
        when(sharingKeyRepository.findById("key-1"))
                .thenReturn(Optional.of(entity));
    }

    private SharingKeyEntity key(String agentId, String ownerUserId) {
        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setId("key-1");
        entity.setSharingKey(RAW_KEY);
        entity.setAgentId(agentId);
        entity.setOwnerUserId(ownerUserId);
        entity.setEnabled(true);
        entity.setAllowedOperations("task:cancel");
        entity.setMaxDailyCalls(50);
        entity.setTodayCalls(0);
        entity.setExpiresAt(LocalDateTime.now().plusDays(1));
        return entity;
    }

    private UserEntity owner(String userId, String tenantId) {
        UserEntity owner = new UserEntity();
        owner.setId(userId);
        owner.setTenantId(tenantId);
        return owner;
    }

    private TaskTerminationCommandCoordinator.TerminationExecutionPlan plan(
            String taskId,
            String ownerUserId,
            String tenantId,
            String logicalAgentId,
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
                        false);
        return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                identity,
                AgentResolveContext.builder()
                        .userId(ownerUserId)
                        .tenantId(tenantId)
                        .requestSource("SHARED_API")
                        .build(),
                terminalStatus,
                terminalStatus == null
                        ? new TaskTerminationCommandCoordinator.CapturedTerminationEffect(
                        TaskTerminationCommandCoordinator.Outcome::accepted)
                        : null);
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

    private record CapturedCommand(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskTerminationCommandCoordinatorTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ATTEMPT_ID = "attempt-1";

    @Mock
    private TaskDispatchFacade facade;
    @Mock
    private CanonicalCommandReceiptPort receipts;
    private TaskTerminationCommandCoordinator coordinator;
    private VerifiedCommandAuthorizationDecision.ServerAuthority authority;

    @BeforeEach
    void setUp() {
        coordinator = new TaskTerminationCommandCoordinator(facade, receipts);
        authority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.policy.v1",
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
    }

    @Test
    void freshCommandPermitsExactlyOneCapturedEffectThenRecordsSafeResult() {
        Command command = command(plan(false));
        when(receipts.prepare(command.envelope(), command.decision()))
                .thenReturn(prepare(CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                        null, null, null));
        when(receipts.beginEffect(command.envelope(), command.decision()))
                .thenReturn(permit(CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                        CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                        ATTEMPT_ID, null, null));
        routeThroughGate(command, null);
        when(receipts.recordResult(
                REQUEST_ID, ATTEMPT_ID, "TASK:task-1",
                TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED))
                .thenReturn(snapshot(
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        ATTEMPT_ID,
                        "TASK:task-1",
                        TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED));

        TaskTerminationCommandCoordinator.TerminationCommandResult result =
                coordinator.execute(command.plan(), command.envelope(), command.decision());

        TaskTerminationCommandCoordinator.Executed executed = assertInstanceOf(
                TaskTerminationCommandCoordinator.Executed.class, result);
        assertEquals("task-1", executed.reference().taskId());
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                executed.outcome().safeCode());
        InOrder order = inOrder(receipts, facade);
        order.verify(receipts).prepare(command.envelope(), command.decision());
        order.verify(facade).executeTerminationPlan(
                eq(command.plan()), any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
        order.verify(receipts).beginEffect(command.envelope(), command.decision());
        order.verify(receipts).recordResult(
                REQUEST_ID, ATTEMPT_ID, "TASK:task-1",
                TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED);
        verify(receipts, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void recordedReplayReturnsOriginalOutcomeWithoutReadOrEffect() {
        Command command = command(plan(false));
        when(receipts.prepare(command.envelope(), command.decision()))
                .thenReturn(prepare(
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        ATTEMPT_ID,
                        "TASK:task-1",
                        "TASK_ALREADY_TERMINAL_ABORTED"));

        TaskTerminationCommandCoordinator.TerminationCommandResult result =
                coordinator.execute(command.plan(), command.envelope(), command.decision());

        TaskTerminationCommandCoordinator.RecordedReplay replay = assertInstanceOf(
                TaskTerminationCommandCoordinator.RecordedReplay.class, result);
        assertEquals("ABORTED", replay.outcome().terminalStatus());
        verifyNoInteractions(facade);
        verify(receipts, never()).beginEffect(any(), any());
        verify(receipts, never()).recordResult(any(), any(), any(), any());

        org.mockito.Mockito.reset(facade, receipts);
        Command raced = command(plan(false));
        when(receipts.prepare(raced.envelope(), raced.decision()))
                .thenReturn(prepare(
                        CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                        null, null, null));
        when(receipts.beginEffect(raced.envelope(), raced.decision()))
                .thenReturn(permit(
                        CanonicalCommandReceiptPort.BeginEffectDisposition.RESULT_RECORDED,
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        ATTEMPT_ID,
                        "TASK:task-1",
                        TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED));
        routeThroughGate(raced, null);

        TaskTerminationCommandCoordinator.RecordedReplay racedReplay = assertInstanceOf(
                TaskTerminationCommandCoordinator.RecordedReplay.class,
                coordinator.execute(raced.plan(), raced.envelope(), raced.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                racedReplay.outcome().safeCode());
        verify(facade).executeTerminationPlan(
                eq(raced.plan()), any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
        verify(receipts, never()).recordResult(any(), any(), any(), any());
    }

    @Test
    void startedAndAmbiguousReceiptsNeverDispatch() {
        for (CanonicalCommandReceiptPort.ReceiptState state : new CanonicalCommandReceiptPort.ReceiptState[]{
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS}) {
            Command command = command(plan(false));
            when(receipts.prepare(command.envelope(), command.decision()))
                    .thenReturn(prepare(state, ATTEMPT_ID, null, "UNKNOWN"));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> coordinator.execute(
                            command.plan(), command.envelope(), command.decision()));

            assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                    failure.getMessage());
        }
        verifyNoInteractions(facade);
        verify(receipts, never()).beginEffect(any(), any());

        for (CanonicalCommandReceiptPort.BeginEffectDisposition disposition
                : new CanonicalCommandReceiptPort.BeginEffectDisposition[]{
                CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED,
                CanonicalCommandReceiptPort.BeginEffectDisposition.AMBIGUOUS}) {
            org.mockito.Mockito.reset(facade, receipts);
            Command command = command(plan(false));
            when(receipts.prepare(command.envelope(), command.decision()))
                    .thenReturn(prepare(
                            CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                            null, null, null));
            CanonicalCommandReceiptPort.ReceiptState state = disposition
                    == CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED
                    ? CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED
                    : CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS;
            when(receipts.beginEffect(command.envelope(), command.decision()))
                    .thenReturn(permit(disposition, state, ATTEMPT_ID, null, null));
            routeThroughGate(command, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> coordinator.execute(
                            command.plan(), command.envelope(), command.decision()));
            assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                    failure.getMessage());
            verify(facade).executeTerminationPlan(
                    eq(command.plan()),
                    any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
        }
    }

    @Test
    void terminalNoOpIsRecordedWithoutClaimingAProviderTerminalTransition() {
        Command command = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED, null, null, null));
        when(receipts.beginEffect(any(), any())).thenReturn(permit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                ATTEMPT_ID, null, null));
        routeThroughGate(command, "COMPLETED");
        when(receipts.recordResult(
                REQUEST_ID, ATTEMPT_ID, "TASK:task-1",
                "TASK_ALREADY_TERMINAL_COMPLETED"))
                .thenReturn(snapshot(
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        ATTEMPT_ID, "TASK:task-1", "TASK_ALREADY_TERMINAL_COMPLETED"));

        TaskTerminationCommandCoordinator.Executed result = assertInstanceOf(
                TaskTerminationCommandCoordinator.Executed.class,
                coordinator.execute(command.plan(), command.envelope(), command.decision()));

        assertEquals("COMPLETED", result.outcome().terminalStatus());
    }

    @Test
    void prePermitPlanDriftFailsWithoutAmbiguousOrEffect() {
        Command command = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED, null, null, null));
        doThrow(new IllegalStateException("TERMINATION_PLAN_IDENTITY_CONFLICT"))
                .when(facade).executeTerminationPlan(
                        eq(command.plan()),
                        any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.execute(
                        command.plan(), command.envelope(), command.decision()));

        assertEquals("TERMINATION_PLAN_IDENTITY_CONFLICT", failure.getMessage());
        verify(receipts, never()).beginEffect(any(), any());
        verify(receipts, never()).markAmbiguous(any(), any(), any());
        verify(facade).executeTerminationPlan(
                eq(command.plan()),
                any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
    }

    @Test
    void postPermitEffectOrRecordFailureBecomesAmbiguousWithoutRetry() {
        Command effectFailureCommand = command(plan(false, () -> {
            throw new UnsupportedOperationException("provider detail");
        }));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED, null, null, null));
        when(receipts.beginEffect(any(), any())).thenReturn(permit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                ATTEMPT_ID, null, null));
        routeThroughGate(effectFailureCommand, null);

        IllegalStateException effectFailure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(
                        effectFailureCommand.plan(), effectFailureCommand.envelope(),
                        effectFailureCommand.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                effectFailure.getMessage());
        assertInstanceOf(UnsupportedOperationException.class, effectFailure.getCause());
        verify(receipts).markAmbiguous(
                REQUEST_ID, ATTEMPT_ID,
                TaskTerminationCommandCoordinator.TERMINATION_OUTCOME_UNKNOWN);
        verify(facade).executeTerminationPlan(
                eq(effectFailureCommand.plan()),
                any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));

        org.mockito.Mockito.reset(facade, receipts);
        Command recordFailureCommand = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED, null, null, null));
        when(receipts.beginEffect(any(), any())).thenReturn(permit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                ATTEMPT_ID, null, null));
        routeThroughGate(recordFailureCommand, null);
        when(receipts.recordResult(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("RECORD_FAILED"));

        IllegalStateException recordFailure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(
                        recordFailureCommand.plan(), recordFailureCommand.envelope(),
                        recordFailureCommand.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                recordFailure.getMessage());
        assertEquals("RECORD_FAILED", recordFailure.getCause().getMessage());
        verify(facade).executeTerminationPlan(
                eq(recordFailureCommand.plan()),
                any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
        verify(receipts).markAmbiguous(
                REQUEST_ID, ATTEMPT_ID,
                TaskTerminationCommandCoordinator.TERMINATION_OUTCOME_UNKNOWN);

        org.mockito.Mockito.reset(facade, receipts);
        Command conflictingRecordCommand = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED, null, null, null));
        when(receipts.beginEffect(any(), any())).thenReturn(permit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                ATTEMPT_ID, null, null));
        routeThroughGate(conflictingRecordCommand, null);
        when(receipts.recordResult(any(), any(), any(), any()))
                .thenReturn(snapshot(
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        ATTEMPT_ID,
                        "TASK:task-other",
                        TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED));

        IllegalStateException recordConflict = assertThrows(IllegalStateException.class,
                () -> coordinator.execute(
                        conflictingRecordCommand.plan(),
                        conflictingRecordCommand.envelope(),
                        conflictingRecordCommand.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                recordConflict.getMessage());
        assertEquals("TERMINATION_RESULT_RECORD_CONFLICT",
                recordConflict.getCause().getMessage());
        verify(receipts).markAmbiguous(
                REQUEST_ID, ATTEMPT_ID,
                TaskTerminationCommandCoordinator.TERMINATION_OUTCOME_UNKNOWN);

        org.mockito.Mockito.reset(facade, receipts);
        Command markFailureCommand = command(plan(false, () -> {
            throw new IllegalStateException("PROVIDER_FAILED");
        }));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                null, null, null));
        when(receipts.beginEffect(any(), any())).thenReturn(permit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                ATTEMPT_ID, null, null));
        routeThroughGate(markFailureCommand, null);
        doThrow(new IllegalStateException("MARK_FAILED"))
                .when(receipts).markAmbiguous(
                        REQUEST_ID, ATTEMPT_ID,
                        TaskTerminationCommandCoordinator.TERMINATION_OUTCOME_UNKNOWN);

        IllegalStateException safeFailure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(
                        markFailureCommand.plan(), markFailureCommand.envelope(),
                        markFailureCommand.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                safeFailure.getMessage());
        assertEquals("PROVIDER_FAILED", safeFailure.getCause().getMessage());
        assertEquals("MARK_FAILED",
                safeFailure.getCause().getSuppressed()[0].getMessage());

        when(receipts.prepare(
                markFailureCommand.envelope(), markFailureCommand.decision()))
                .thenReturn(prepare(
                        CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                        ATTEMPT_ID, null,
                        TaskTerminationCommandCoordinator.TERMINATION_OUTCOME_UNKNOWN));

        IllegalStateException retryFailure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(
                        markFailureCommand.plan(), markFailureCommand.envelope(),
                        markFailureCommand.decision()));
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_EFFECT_AMBIGUOUS,
                retryFailure.getMessage());
        verify(facade).executeTerminationPlan(
                eq(markFailureCommand.plan()),
                any(TaskTerminationCommandCoordinator.TerminationEffectGate.class));
        verify(receipts).beginEffect(
                markFailureCommand.envelope(), markFailureCommand.decision());
    }

    @Test
    void planBindingCoversForceAndRejectsTargetOrOwnerDriftBeforeReceipt() {
        TaskTerminationCommandCoordinator.TerminationExecutionPlan normal = plan(false);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan force = plan(true);
        TaskTerminationCommandCoordinator.PlanBinding normalBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(normal);
        TaskTerminationCommandCoordinator.PlanBinding forceBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(force);
        assertNotEquals(normalBinding.effect(), forceBinding.effect());
        assertEquals("TerminationExecutionPlan[content-free]", normal.toString());

        Command normalCommand = command(normal);
        CanonicalCommandEnvelope.CommandBinding original = normalCommand.envelope().binding();
        CanonicalCommandEnvelope.CommandBinding ownerDrift =
                new CanonicalCommandEnvelope.CommandBinding(
                        original.commandKind(), original.ingress(), original.request(),
                        original.actor(),
                        new CanonicalCommandEnvelope.Ownership(
                                original.ownership().tenantReference(), "user-other", null, null),
                        original.target(), original.effect());
        VerifiedCommandAuthorizationDecision driftDecision = authority.issue(ownerDrift);
        CanonicalCommandEnvelope driftEnvelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                ownerDrift,
                driftDecision.metadata());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.execute(normal, driftEnvelope, driftDecision));
        assertEquals("TERMINATION_OWNERSHIP_CONFLICT", failure.getMessage());
        verifyNoInteractions(receipts, facade);
    }

    @Test
    void invalidRecordedReferenceOrOutcomeFailsWithoutProviderEffect() {
        Command command = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                ATTEMPT_ID, "PROVIDER:task-1", "UNKNOWN"));

        IllegalStateException invalidReference = assertThrows(IllegalStateException.class,
                () -> coordinator.execute(
                        command.plan(), command.envelope(), command.decision()));

        assertEquals("TERMINATION_RECORDED_REFERENCE_INVALID",
                invalidReference.getMessage());
        verifyNoInteractions(facade);

        org.mockito.Mockito.reset(facade, receipts);
        Command crossTask = command(plan(false));
        when(receipts.prepare(any(), any())).thenReturn(prepare(
                CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                ATTEMPT_ID,
                "TASK:task-other",
                TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED));

        IllegalStateException taskConflict = assertThrows(IllegalStateException.class,
                () -> coordinator.execute(
                        crossTask.plan(), crossTask.envelope(), crossTask.decision()));

        assertEquals("TERMINATION_RECORDED_TASK_CONFLICT", taskConflict.getMessage());
        verifyNoInteractions(facade);
    }

    private TaskTerminationCommandCoordinator.TerminationExecutionPlan plan(boolean force) {
        return plan(force, TaskTerminationCommandCoordinator.Outcome::accepted);
    }

    private TaskTerminationCommandCoordinator.TerminationExecutionPlan plan(
            boolean force,
            Supplier<TaskTerminationCommandCoordinator.Outcome> effect) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity =
                new TaskTerminationCommandCoordinator.TerminationIdentity(
                        "task-1", "user-1", "tenant-1", "session-1",
                        "provider-task-1", "agent-1", "codex-worker", "worker-1",
                        "directory-1", "gpt-5.4", "model-config-1",
                        "runtime-1", 3, "CODEX", "instance-1", 7L,
                        TaskTerminationCommandCoordinator.ExecutionRoute.PROVIDER,
                        force);
        return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                identity,
                AgentResolveContext.builder()
                        .userId("user-1")
                        .tenantId("tenant-1")
                        .requestSource("UI")
                        .build(),
                null,
                new TaskTerminationCommandCoordinator.CapturedTerminationEffect(effect));
    }

    private void routeThroughGate(Command command, String terminalStatus) {
        when(facade.executeTerminationPlan(
                eq(command.plan()),
                any(TaskTerminationCommandCoordinator.TerminationEffectGate.class)))
                .thenAnswer(invocation -> {
                    TaskTerminationCommandCoordinator.TerminationEffectGate gate =
                            invocation.getArgument(1);
                    return gate.invoke(command.plan(), () -> terminalStatus);
                });
    }

    private Command command(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        TaskTerminationCommandCoordinator.PlanBinding planBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                                "NAVIGATOR_UI",
                                "/api/v1/tasks/{taskId}/cancel"),
                        new CanonicalCommandEnvelope.Request(
                                REQUEST_ID, REQUEST_ID, REQUEST_ID),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                AuthorizationCredentialLane.NAVIGATOR_JWT,
                                "principal-fingerprint",
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                planBinding.tenantReference(), "user-1", null, null),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        return new Command(
                plan,
                new CanonicalCommandEnvelope(
                        CanonicalCommandEnvelope.SCHEMA_VERSION,
                        binding,
                        decision.metadata()),
                decision);
    }

    private CanonicalCommandReceiptPort.PrepareResult prepare(
            CanonicalCommandReceiptPort.ReceiptState state,
            String attempt,
            String reference,
            String safeCode) {
        return new CanonicalCommandReceiptPort.PrepareResult(
                CanonicalCommandReceiptPort.PrepareDisposition.CREATED,
                snapshot(state, attempt, reference, safeCode));
    }

    private CanonicalCommandReceiptPort.EffectPermit permit(
            CanonicalCommandReceiptPort.BeginEffectDisposition disposition,
            CanonicalCommandReceiptPort.ReceiptState state,
            String attempt,
            String reference,
            String safeCode) {
        return new CanonicalCommandReceiptPort.EffectPermit(
                disposition,
                snapshot(state, attempt, reference, safeCode));
    }

    private CanonicalCommandReceiptPort.ReceiptSnapshot snapshot(
            CanonicalCommandReceiptPort.ReceiptState state,
            String attempt,
            String reference,
            String safeCode) {
        return new CanonicalCommandReceiptPort.ReceiptSnapshot(
                "receipt-1", REQUEST_ID, state, attempt, reference, safeCode,
                "decision-1", null, null, null,
                null, null, null, null, 0L);
    }

    private record Command(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan,
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

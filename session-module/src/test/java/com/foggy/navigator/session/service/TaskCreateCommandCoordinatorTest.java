package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.command.CommandOnceReceiptService;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskCreateCommandCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private CommandOnceReceiptService receiptService;

    private TaskCreateCommandCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new TaskCreateCommandCoordinator(taskDispatchFacade, receiptService);
    }

    @Test
    void planBindingMapsBothRoutesAndRejectsEveryPlanFactDrift() {
        TaskCreateTargetResolver.CreateExecutionPlan direct = plan(
                null, "owner-1", null, "codex-worker", "worker-1",
                "model-config-1", "gpt-5.6", "session-1", "directory-1",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT);
        TaskCreateCommandCoordinator.PlanBinding directBinding =
                TaskCreateCommandCoordinator.PlanBinding.from(direct);

        assertEquals(CanonicalCommandEnvelope.TargetKind.RUNTIME,
                directBinding.target().kind());
        assertNull(directBinding.target().logicalAgentId());
        assertEquals(directBinding.effect().effectScopeReference(),
                directBinding.target().targetId());
        assertEquals(
                "TASK_CREATE_SCOPE_LP_UTF8_SHA256_V1:"
                        + "ddbfd64900d6e53431a401420a3e30b6c01685fff4b1f5a2ede5f0593def19c9",
                directBinding.effect().effectScopeReference());
        assertEquals("navi.tenant.absent.v1", directBinding.tenantReference());

        TaskCreateTargetResolver.CreateExecutionPlan a2a = plan(
                "tenant-1", "owner-1", "agent-1", "claude-worker", "worker-1",
                "model-config-1", "claude-sonnet", "session-1", "directory-1",
                TaskCreateTargetResolver.ExecutionRoute.A2A);
        TaskCreateCommandCoordinator.PlanBinding a2aBinding =
                TaskCreateCommandCoordinator.PlanBinding.from(a2a);
        assertEquals(CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                a2aBinding.target().kind());
        assertEquals("agent-1", a2aBinding.target().targetId());
        assertEquals("navi.tenant.present.v1:tenant-1", a2aBinding.tenantReference());
        assertNotEquals(directBinding.tenantReference(), a2aBinding.tenantReference());

        Issued directIssued = issue(direct, "request-binding-1",
                CanonicalCommandEnvelope.CommandIngress.OPENAPI);
        assertDoesNotThrow(() -> directBinding.requireEnvelope(directIssued.envelope()));
        assertEquals(CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                directIssued.envelope().binding().ingress().ingress(),
                "trusted ingress must stay independent from DIRECT execution route");

        for (TaskCreateTargetResolver.CreateExecutionPlan drifted : new TaskCreateTargetResolver.CreateExecutionPlan[]{
                plan(null, "owner-1", null, "codex-worker", "worker-1",
                        "model-config-1", "gpt-drift", "session-1", "directory-1",
                        TaskCreateTargetResolver.ExecutionRoute.DIRECT),
                plan(null, "owner-1", null, "codex-worker", "worker-1",
                        "model-config-1", "gpt-5.6", "session-1", "directory-drift",
                        TaskCreateTargetResolver.ExecutionRoute.DIRECT),
                plan(null, "owner-1", null, "codex-worker", "worker-1",
                        "model-config-1", "gpt-5.6", "session-1", "directory-1",
                        TaskCreateTargetResolver.ExecutionRoute.A2A)
        }) {
            TaskCreateCommandCoordinator.PlanBinding driftedBinding =
                    TaskCreateCommandCoordinator.PlanBinding.from(drifted);
            assertNotEquals(directBinding.effect().effectScopeReference(),
                    driftedBinding.effect().effectScopeReference());
            assertThrows(IllegalStateException.class,
                    () -> driftedBinding.requireEnvelope(directIssued.envelope()));
        }
    }

    @Test
    void receiptReplayStatesReturnReferenceOrFailClosedWithoutCallback() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        Issued issued = issue(plan, "request-replay-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);

        when(receiptService.prepare(issued.envelope(), issued.decision()))
                .thenReturn(prepare("request-replay-1",
                        CommandOnceReceiptService.ReceiptState.RESULT_RECORDED,
                        "attempt-recorded", "TASK:task-recorded"));

        TaskCreateCommandCoordinator.TaskCreateCommandResult result = coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision());

        TaskCreateCommandCoordinator.RecordedReplay replay = assertInstanceOf(
                TaskCreateCommandCoordinator.RecordedReplay.class, result);
        assertEquals("task-recorded", replay.reference().taskId());
        verifyNoInteractions(taskDispatchFacade);

        reset(receiptService, taskDispatchFacade);
        when(receiptService.prepare(any(), any()))
                .thenReturn(prepare("request-replay-1",
                        CommandOnceReceiptService.ReceiptState.EFFECT_STARTED,
                        "attempt-started", null));
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));
        verifyNoInteractions(taskDispatchFacade);

        reset(receiptService, taskDispatchFacade);
        when(receiptService.prepare(any(), any()))
                .thenReturn(prepare("request-replay-1",
                        CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                        "attempt-ambiguous", null));
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));
        verifyNoInteractions(taskDispatchFacade);
    }

    @Test
    void beginEffectRacesReturnRecordedReferenceOrFailClosedBeforeProvider() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        AtomicInteger providerEffects = new AtomicInteger();

        for (CommandOnceReceiptService.BeginEffectDisposition disposition
                : new CommandOnceReceiptService.BeginEffectDisposition[]{
                CommandOnceReceiptService.BeginEffectDisposition.RESULT_RECORDED,
                CommandOnceReceiptService.BeginEffectDisposition.ALREADY_STARTED,
                CommandOnceReceiptService.BeginEffectDisposition.AMBIGUOUS}) {
            reset(receiptService, taskDispatchFacade);
            String requestId = "request-begin-race-" + disposition.name().toLowerCase();
            Issued issued = issue(plan, requestId,
                    CanonicalCommandEnvelope.CommandIngress.DIRECT);
            CommandOnceReceiptService.ReceiptState state = switch (disposition) {
                case RESULT_RECORDED -> CommandOnceReceiptService.ReceiptState.RESULT_RECORDED;
                case ALREADY_STARTED -> CommandOnceReceiptService.ReceiptState.EFFECT_STARTED;
                case AMBIGUOUS -> CommandOnceReceiptService.ReceiptState.AMBIGUOUS;
                case PERMITTED -> throw new IllegalStateException("not a race disposition");
            };
            String reference = disposition
                    == CommandOnceReceiptService.BeginEffectDisposition.RESULT_RECORDED
                    ? "TASK:task-race-recorded"
                    : null;
            CommandOnceReceiptService.EffectPermit effectPermit = effectPermit(
                    disposition, requestId, state, "attempt-race", reference);
            when(receiptService.prepare(any(), any())).thenReturn(prepared(requestId));
            when(receiptService.beginEffect(any(), any())).thenReturn(effectPermit);
            when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                    .thenAnswer(invocation -> {
                        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                                invocation.getArgument(3);
                        return gate.invoke(plan, actualIdentity(request, context), () -> {
                            providerEffects.incrementAndGet();
                            return exactTask("must-not-run");
                        });
                    });

            if (disposition == CommandOnceReceiptService.BeginEffectDisposition.RESULT_RECORDED) {
                TaskCreateCommandCoordinator.RecordedReplay replay = assertInstanceOf(
                        TaskCreateCommandCoordinator.RecordedReplay.class,
                        coordinator.execute(
                                request, context, plan, issued.envelope(), issued.decision()));
                assertEquals("task-race-recorded", replay.reference().taskId());
            } else {
                assertThrows(IllegalStateException.class, () -> coordinator.execute(
                        request, context, plan, issued.envelope(), issued.decision()));
            }
            assertEquals(0, providerEffects.get());
            verify(receiptService, never()).recordResult(any(), any(), any(), any());
            verify(receiptService, never()).markAmbiguous(any(), any(), any());
        }
    }

    @Test
    void successfulCreateUsesOneEffectGateAndRecordsExactTaskReference() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        Issued issued = issue(plan, "request-success-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);
        AtomicInteger providerEffects = new AtomicInteger();
        DispatchTaskDTO task = exactTask("task-success-1");
        CommandOnceReceiptService.EffectPermit effectPermit =
                permitted("request-success-1", "attempt-success-1");

        when(receiptService.prepare(issued.envelope(), issued.decision()))
                .thenReturn(prepared("request-success-1"));
        when(receiptService.beginEffect(issued.envelope(), issued.decision()))
                .thenReturn(effectPermit);
        when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    return gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        return task;
                    });
                });

        TaskCreateCommandCoordinator.TaskCreateCommandResult result = coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision());

        TaskCreateCommandCoordinator.Executed executed = assertInstanceOf(
                TaskCreateCommandCoordinator.Executed.class, result);
        assertSame(task, executed.freshTask());
        assertEquals("TASK:task-success-1", executed.reference().opaqueValue());
        assertEquals(1, providerEffects.get());
        verify(receiptService).beginEffect(issued.envelope(), issued.decision());
        verify(receiptService).recordResult(
                "request-success-1", "attempt-success-1",
                "TASK:task-success-1", TaskCreateCommandCoordinator.TASK_CREATED);
        verify(receiptService, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void secondGateInvocationRejectsBeforeSecondSupplier() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        Issued issued = issue(plan, "request-double-gate-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);
        AtomicInteger providerEffects = new AtomicInteger();
        CommandOnceReceiptService.EffectPermit effectPermit =
                permitted("request-double-gate-1", "attempt-double-1");

        when(receiptService.prepare(any(), any()))
                .thenReturn(prepared("request-double-gate-1"));
        when(receiptService.beginEffect(any(), any()))
                .thenReturn(effectPermit);
        when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        return exactTask("task-first");
                    });
                    return gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        return exactTask("task-second");
                    });
                });

        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));

        assertEquals(1, providerEffects.get());
        verify(receiptService).markAmbiguous(
                "request-double-gate-1", "attempt-double-1",
                TaskCreateCommandCoordinator.TASK_CREATE_OUTCOME_UNKNOWN);
        verify(receiptService, never()).recordResult(any(), any(), any(), any());
    }

    @Test
    void failureBeforePermitLeavesPreparedAndRetryable() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(" owner-1 ")
                .sessionId("session-1")
                .modelConfigId("model-config-1")
                .requestSource("UI")
                .build();
        Issued issued = issue(plan, "request-pre-permit-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);
        AtomicInteger providerEffects = new AtomicInteger();

        when(receiptService.prepare(any(), any()))
                .thenReturn(prepared("request-pre-permit-1"));
        when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    return gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        return exactTask("must-not-run");
                    });
                });

        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));

        assertEquals(0, providerEffects.get());
        verify(receiptService, never()).beginEffect(any(), any());
        verify(receiptService, never()).markAmbiguous(any(), any(), any());
        verify(receiptService, never()).recordResult(any(), any(), any(), any());
    }

    @Test
    void failureAfterPermitMarksAmbiguousAndNeverRedispatches() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        Issued issued = issue(plan, "request-provider-failure-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);
        AtomicInteger providerEffects = new AtomicInteger();
        CommandOnceReceiptService.EffectPermit effectPermit = permitted(
                "request-provider-failure-1", "attempt-provider-failure-1");

        when(receiptService.prepare(any(), any()))
                .thenReturn(prepared("request-provider-failure-1"),
                        prepare("request-provider-failure-1",
                                CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                                "attempt-provider-failure-1", null));
        when(receiptService.beginEffect(any(), any()))
                .thenReturn(effectPermit);
        when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    return gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        throw new IllegalStateException("provider unavailable");
                    });
                });

        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));

        assertEquals(1, providerEffects.get());
        verify(taskDispatchFacade, times(1)).createTask(eq(request), eq(context), eq(plan), any());
        verify(receiptService).markAmbiguous(
                "request-provider-failure-1", "attempt-provider-failure-1",
                TaskCreateCommandCoordinator.TASK_CREATE_OUTCOME_UNKNOWN);
    }

    @Test
    void resultRecordingFailureNeverRedispatchesOrSynthesizesReference() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        Issued issued = issue(plan, "request-record-failure-1",
                CanonicalCommandEnvelope.CommandIngress.DIRECT);
        AtomicInteger providerEffects = new AtomicInteger();
        CommandOnceReceiptService.EffectPermit effectPermit = permitted(
                "request-record-failure-1", "attempt-record-failure-1");

        when(receiptService.prepare(any(), any()))
                .thenReturn(prepared("request-record-failure-1"),
                        prepare("request-record-failure-1",
                                CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                                "attempt-record-failure-1", null));
        when(receiptService.beginEffect(any(), any()))
                .thenReturn(effectPermit);
        when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    return gate.invoke(plan, actualIdentity(request, context), () -> {
                        providerEffects.incrementAndGet();
                        return exactTask("task-record-failure-1");
                    });
                });
        doThrow(new IllegalStateException("receipt store unavailable"))
                .when(receiptService).recordResult(
                        "request-record-failure-1", "attempt-record-failure-1",
                        "TASK:task-record-failure-1", TaskCreateCommandCoordinator.TASK_CREATED);

        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                request, context, plan, issued.envelope(), issued.decision()));

        assertEquals(1, providerEffects.get());
        verify(taskDispatchFacade, times(1)).createTask(eq(request), eq(context), eq(plan), any());
        verify(receiptService).markAmbiguous(
                "request-record-failure-1", "attempt-record-failure-1",
                TaskCreateCommandCoordinator.TASK_CREATE_OUTCOME_UNKNOWN);
    }

    @Test
    void invalidProviderResultMarksAmbiguousWithoutRecordingSyntheticReference() {
        TaskCreateTargetResolver.CreateExecutionPlan plan = directPlan();
        TaskDispatchRequest request = directRequest();
        AgentResolveContext context = directContext();
        DispatchTaskDTO[] invalidResults = new DispatchTaskDTO[]{
                DispatchTaskDTO.builder()
                        .taskId(" ")
                        .providerType("codex-worker")
                        .build(),
                DispatchTaskDTO.builder()
                        .taskId("task-identity-conflict")
                        .providerType("claude-worker")
                        .build(),
                DispatchTaskDTO.builder()
                        .taskId("task-whitespace-conflict")
                        .providerType(" codex-worker ")
                        .build()
        };

        for (int index = 0; index < invalidResults.length; index++) {
            reset(receiptService, taskDispatchFacade);
            String requestId = "request-invalid-result-" + index;
            String attemptId = "attempt-invalid-result-" + index;
            DispatchTaskDTO invalidResult = invalidResults[index];
            Issued issued = issue(plan, requestId,
                    CanonicalCommandEnvelope.CommandIngress.DIRECT);
            CommandOnceReceiptService.EffectPermit effectPermit =
                    permitted(requestId, attemptId);
            when(receiptService.prepare(any(), any())).thenReturn(prepared(requestId));
            when(receiptService.beginEffect(any(), any())).thenReturn(effectPermit);
            when(taskDispatchFacade.createTask(eq(request), eq(context), eq(plan), any()))
                    .thenAnswer(invocation -> {
                        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                                invocation.getArgument(3);
                        return gate.invoke(
                                plan,
                                actualIdentity(request, context),
                                () -> invalidResult);
                    });

            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    request, context, plan, issued.envelope(), issued.decision()));

            verify(receiptService).markAmbiguous(
                    requestId,
                    attemptId,
                    TaskCreateCommandCoordinator.TASK_CREATE_OUTCOME_UNKNOWN);
            verify(receiptService, never()).recordResult(any(), any(), any(), any());
        }
    }

    private static TaskCreateTargetResolver.CreateExecutionPlan directPlan() {
        return plan(null, "owner-1", null, "codex-worker", "worker-1",
                "model-config-1", "gpt-5.6", "session-1", "directory-1",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT);
    }

    private static TaskCreateTargetResolver.CreateExecutionPlan plan(
            String tenantId,
            String ownerUserId,
            String logicalAgentId,
            String providerType,
            String physicalWorkerId,
            String modelConfigId,
            String model,
            String sessionId,
            String directoryId,
            TaskCreateTargetResolver.ExecutionRoute executionRoute) {
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        when(plan.tenantId()).thenReturn(tenantId);
        when(plan.ownerUserId()).thenReturn(ownerUserId);
        when(plan.logicalAgentId()).thenReturn(logicalAgentId);
        when(plan.providerType()).thenReturn(providerType);
        when(plan.physicalWorkerId()).thenReturn(physicalWorkerId);
        when(plan.modelConfigId()).thenReturn(modelConfigId);
        when(plan.model()).thenReturn(model);
        when(plan.sessionId()).thenReturn(sessionId);
        when(plan.directoryId()).thenReturn(directoryId);
        when(plan.executionRoute()).thenReturn(executionRoute);
        return plan;
    }

    private static TaskDispatchRequest directRequest() {
        return TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .modelConfigId("model-config-1")
                .model("gpt-5.6")
                .sessionId("session-1")
                .directoryId("directory-1")
                .prompt("content is intentionally outside the command envelope")
                .build();
    }

    private static AgentResolveContext directContext() {
        return AgentResolveContext.builder()
                .userId("owner-1")
                .sessionId("session-1")
                .modelConfigId("model-config-1")
                .requestSource("UI")
                .build();
    }

    private static TaskCreateCommandCoordinator.ProviderEffectIdentity actualIdentity(
            TaskDispatchRequest request, AgentResolveContext context) {
        return TaskCreateCommandCoordinator.ProviderEffectIdentity.atEffectPoint(
                TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                request,
                context,
                null,
                "codex-worker");
    }

    private static DispatchTaskDTO exactTask(String taskId) {
        return DispatchTaskDTO.builder()
                .taskId(taskId)
                .providerType("codex-worker")
                .workerId("worker-1")
                .modelConfigId("model-config-1")
                .model("gpt-5.6")
                .sessionId("session-1")
                .directoryId("directory-1")
                .build();
    }

    private static CommandOnceReceiptService.PrepareResult prepared(String requestId) {
        return prepare(requestId, CommandOnceReceiptService.ReceiptState.PREPARED, null, null);
    }

    private static CommandOnceReceiptService.PrepareResult prepare(
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String attemptId,
            String resultReference) {
        return new CommandOnceReceiptService.PrepareResult(
                CommandOnceReceiptService.PrepareDisposition.EXACT_REPLAY,
                snapshot(requestId, state, attemptId, resultReference));
    }

    private static CommandOnceReceiptService.EffectPermit permitted(
            String requestId, String attemptId) {
        return effectPermit(
                CommandOnceReceiptService.BeginEffectDisposition.PERMITTED,
                requestId,
                CommandOnceReceiptService.ReceiptState.EFFECT_STARTED,
                attemptId,
                null);
    }

    private static CommandOnceReceiptService.EffectPermit effectPermit(
            CommandOnceReceiptService.BeginEffectDisposition disposition,
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String attemptId,
            String resultReference) {
        CommandOnceReceiptService.EffectPermit permit =
                mock(CommandOnceReceiptService.EffectPermit.class);
        when(permit.disposition()).thenReturn(disposition);
        lenient().when(permit.providerEffectPermitted()).thenReturn(
                disposition == CommandOnceReceiptService.BeginEffectDisposition.PERMITTED);
        lenient().when(permit.snapshot()).thenReturn(snapshot(
                requestId,
                state,
                attemptId,
                resultReference));
        return permit;
    }

    private static CommandOnceReceiptService.ReceiptSnapshot snapshot(
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String attemptId,
            String resultReference) {
        return new CommandOnceReceiptService.ReceiptSnapshot(
                "receipt-" + requestId,
                requestId,
                state,
                attemptId,
                resultReference,
                state.name(),
                "decision-1",
                NOW,
                NOW,
                NOW.plusSeconds(300),
                null,
                null,
                null,
                null,
                0L);
    }

    private static Issued issue(
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            String requestId,
            CanonicalCommandEnvelope.CommandIngress ingress) {
        TaskCreateCommandCoordinator.PlanBinding planBinding =
                TaskCreateCommandCoordinator.PlanBinding.from(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.CREATE,
                        new CanonicalCommandEnvelope.Ingress(
                                ingress, "test-surface", "test.task.create"),
                        new CanonicalCommandEnvelope.Request(
                                requestId, "idempotency-" + requestId,
                                "correlation-" + requestId),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                AuthorizationCredentialLane.NAVIGATOR_JWT,
                                "principal-owner-1",
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                planBinding.tenantReference(),
                                plan.ownerUserId(),
                                null,
                                null),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision.ServerAuthority authority =
                new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "test-policy-v1",
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(5));
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        return new Issued(new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata()), decision);
    }

    private record Issued(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

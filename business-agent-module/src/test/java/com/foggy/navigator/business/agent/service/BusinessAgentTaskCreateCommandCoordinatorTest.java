package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessAgentTaskCreateCommandCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final LocalDateTime LOCAL_NOW =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String TASK_TOKEN = "plain-token-must-stay-in-memory";

    @Mock
    private CanonicalCommandReceiptPort receiptPort;
    @Mock
    private BusinessAgentTaskService taskService;

    private BusinessAgentTaskCreateCommandCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new BusinessAgentTaskCreateCommandCoordinator(receiptPort, taskService);
    }

    @Test
    void planBindingMapsContentFreeFactsAndRejectsPlanDriftBeforeReceipt() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        BusinessAgentTaskCreateCommandCoordinator.PlanBinding planBinding =
                BusinessAgentTaskCreateCommandCoordinator.PlanBinding.from(prepared);
        Issued issued = issue(prepared, "request-binding-1");

        assertEquals(issued.envelope().binding().ownership(), planBinding.ownership());
        assertEquals(issued.envelope().binding().target(), planBinding.target());
        assertEquals(issued.envelope().binding().effect(), planBinding.effect());
        assertEquals(CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                planBinding.target().kind());
        assertEquals("agent-1", planBinding.target().targetId());
        assertEquals("worker-1", planBinding.target().physicalWorkerId());
        assertEquals("langgraph-biz-worker", planBinding.target().providerType());
        assertTrue(planBinding.ownership().tenantReference()
                .startsWith("navi.tenant.present.v1:"));
        assertTrue(planBinding.ownership().upstreamReference()
                .startsWith("BUSINESS_UPSTREAM_SHA256:"));
        assertFalse(planBinding.ownership().upstreamReference().contains("upstream-system-1"));
        assertFalse(planBinding.ownership().upstreamReference().contains("upstream-user-1"));
        assertTrue(planBinding.effect().effectScopeReference()
                .startsWith("BUSINESS_TASK_CREATE_RECEIPT_SCOPE_SHA256_V1:"));
        assertFalse(planBinding.toString().contains("upstream-user-1"));

        CanonicalCommandEnvelope.CommandBinding original = issued.envelope().binding();
        List<CanonicalCommandEnvelope.CommandBinding> drifted = List.of(
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        original.ingress(), original.request(), original.actor(),
                        original.ownership(), original.target(), original.effect()),
                new CanonicalCommandEnvelope.CommandBinding(
                        original.commandKind(), original.ingress(), original.request(), original.actor(),
                        new CanonicalCommandEnvelope.Ownership(
                                original.ownership().tenantReference(),
                                "actor-drift",
                                original.ownership().clientAppReference(),
                                original.ownership().upstreamReference()),
                        original.target(), original.effect()),
                new CanonicalCommandEnvelope.CommandBinding(
                        original.commandKind(), original.ingress(), original.request(), original.actor(),
                        original.ownership(),
                        new CanonicalCommandEnvelope.Target(
                                CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                                "agent-drift", "agent-drift",
                                original.target().providerType(),
                                original.target().physicalWorkerId(),
                                original.target().modelConfigId(),
                                null,
                                original.target().sessionId()),
                        original.effect()),
                new CanonicalCommandEnvelope.CommandBinding(
                        original.commandKind(), original.ingress(), original.request(), original.actor(),
                        original.ownership(), original.target(),
                        new CanonicalCommandEnvelope.Effect(
                                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATE_ACTION,
                                "BUSINESS_TASK_CREATE_RECEIPT_SCOPE_SHA256_V1:drift")));

        for (CanonicalCommandEnvelope.CommandBinding binding : drifted) {
            CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                    CanonicalCommandEnvelope.SCHEMA_VERSION,
                    binding,
                    issued.envelope().authorizationMetadata());
            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    prepared, envelope, issued.decision()));
        }
        verifyNoInteractions(receiptPort, taskService);
    }

    @Test
    void receiptBindingUsesExactRawContextAndKeepsEveryOtherPlanGroupBound() {
        BusinessAgentTaskCreatePlan unresolved = withResolvedContext(plan(true), null);
        BusinessAgentTaskCreatePlan generated =
                withResolvedContext(unresolved, "bctx-generated-after-create");
        BusinessAgentTaskPreparedFreshCreate unresolvedPrepared = prepared(unresolved, null);
        BusinessAgentTaskPreparedFreshCreate generatedPrepared = prepared(generated, null);

        assertNotEquals(unresolved.semanticFingerprint(), generated.semanticFingerprint());
        assertEquals(receiptScope(unresolvedPrepared), receiptScope(generatedPrepared));

        List<String> exactRawContextScopes = List.of(
                receiptScope(prepared(unresolved, null)),
                receiptScope(prepared(unresolved, "")),
                receiptScope(prepared(unresolved, " ")),
                receiptScope(prepared(unresolved, " context-1 ")),
                receiptScope(prepared(unresolved, "context-1")));
        assertEquals(5, new HashSet<>(exactRawContextScopes).size());

        String baselineScope = receiptScope(unresolvedPrepared);
        for (BusinessAgentTaskCreatePlan drifted : List.of(
                withActorUserId(unresolved, "actor-drift"),
                withAgentId(unresolved, "agent-drift"),
                withModelName(unresolved, "model-drift"),
                withWorkdir(unresolved, "/workspace/drift"),
                withAllowedTools(unresolved, List.of("write_file")))) {
            assertNotEquals(baselineScope, receiptScope(prepared(drifted, null)));
        }

        String safeProjection = baselineScope
                + BusinessAgentTaskCreateCommandCoordinator.PlanBinding
                        .from(unresolvedPrepared)
                        .toString();
        assertFalse(safeProjection.contains("bctx-generated-after-create"));
        assertFalse(safeProjection.contains("client-context-must-not-escape"));
        assertFalse(safeProjection.contains(" context-1 "));
    }

    @Test
    void rawNullReplaySurvivesGeneratedResolvedContextAndExecutesFreshOnlyOnce() {
        BusinessAgentTaskCreatePlan unresolved = withResolvedContext(plan(true), null);
        BusinessAgentTaskPreparedFreshCreate first = prepared(unresolved, null);
        BusinessAgentTaskCreatePlan generated =
                withResolvedContext(unresolved, "bctx-generated-after-create");
        BusinessAgentTaskPreparedFreshCreate retry = prepared(generated, null);
        Issued issued = issue(first, "request-generated-context-replay-1");
        CreatedBusinessAgentTaskDTO freshTask = exactTask(unresolved);
        freshTask.setContextId("bctx-generated-after-create");

        when(receiptPort.prepare(issued.envelope(), issued.decision()))
                .thenReturn(
                        preparedReceipt("request-generated-context-replay-1"),
                        new CanonicalCommandReceiptPort.PrepareResult(
                                CanonicalCommandReceiptPort.PrepareDisposition.EXACT_REPLAY,
                                recorded(
                                        "request-generated-context-replay-1",
                                        "attempt-generated-context-replay-1",
                                        "BUSINESS_TASK:business-task-1")));
        when(receiptPort.beginEffect(issued.envelope(), issued.decision()))
                .thenReturn(permitted(
                        "request-generated-context-replay-1",
                        "attempt-generated-context-replay-1"));
        when(taskService.executeFreshCreatePlan(first)).thenReturn(freshTask);
        when(receiptPort.recordResult(any(), any(), any(), any()))
                .thenReturn(recorded(
                        "request-generated-context-replay-1",
                        "attempt-generated-context-replay-1",
                        "BUSINESS_TASK:business-task-1"));

        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.Executed.class,
                coordinator.execute(first, issued.envelope(), issued.decision()));
        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.RecordedReplay.class,
                coordinator.execute(retry, issued.envelope(), issued.decision()));

        verify(taskService).executeFreshCreatePlan(first);
        verify(taskService, never()).executeFreshCreatePlan(retry);
        verify(receiptPort).beginEffect(issued.envelope(), issued.decision());
        verify(receiptPort).recordResult(any(), any(), any(), any());
    }

    @Test
    void successfulCreateRecordsOnlyAfterFreshProxyReturns() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-success-1");
        CreatedBusinessAgentTaskDTO freshTask = exactTask(plan);
        boolean[] freshReturned = {false};

        when(receiptPort.prepare(issued.envelope(), issued.decision()))
                .thenReturn(preparedReceipt("request-success-1"));
        when(receiptPort.beginEffect(issued.envelope(), issued.decision()))
                .thenReturn(permitted("request-success-1", "attempt-success-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenAnswer(invocation -> {
            freshReturned[0] = true;
            return freshTask;
        });
        when(receiptPort.recordResult(
                "request-success-1",
                "attempt-success-1",
                "BUSINESS_TASK:business-task-1",
                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATED))
                .thenAnswer(invocation -> {
                    assertTrue(freshReturned[0]);
                    assertFalse(invocation.getArgument(2, String.class).contains(TASK_TOKEN));
                    return recorded(
                            "request-success-1",
                            "attempt-success-1",
                            "BUSINESS_TASK:business-task-1");
                });

        BusinessAgentTaskCreateCommandCoordinator.BusinessTaskCreateCommandResult result =
                coordinator.execute(prepared, issued.envelope(), issued.decision());

        BusinessAgentTaskCreateCommandCoordinator.Executed executed = assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.Executed.class, result);
        assertSame(freshTask, executed.freshTask());
        assertEquals("business-task-1", executed.reference().taskId());
        assertFalse(executed.toString().contains(TASK_TOKEN));
        assertFalse(executed.toString().contains(freshTask.toString()));
        InOrder order = inOrder(receiptPort, taskService);
        order.verify(receiptPort).prepare(issued.envelope(), issued.decision());
        order.verify(receiptPort).beginEffect(issued.envelope(), issued.decision());
        order.verify(taskService).executeFreshCreatePlan(prepared);
        order.verify(receiptPort).recordResult(
                "request-success-1",
                "attempt-success-1",
                "BUSINESS_TASK:business-task-1",
                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATED);
        verify(receiptPort, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void recordedPrepareAndBeginRaceReturnReferenceWithoutFreshEffect() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-replay-1");
        when(receiptPort.prepare(issued.envelope(), issued.decision()))
                .thenReturn(new CanonicalCommandReceiptPort.PrepareResult(
                        CanonicalCommandReceiptPort.PrepareDisposition.EXACT_REPLAY,
                        recorded(
                                "request-replay-1",
                                "attempt-replay-1",
                                "BUSINESS_TASK:business-task-replay")));

        BusinessAgentTaskCreateCommandCoordinator.RecordedReplay prepareReplay =
                assertInstanceOf(
                        BusinessAgentTaskCreateCommandCoordinator.RecordedReplay.class,
                        coordinator.execute(prepared, issued.envelope(), issued.decision()));
        assertEquals("business-task-replay", prepareReplay.reference().taskId());
        verify(receiptPort, never()).beginEffect(any(), any());
        verifyNoInteractions(taskService);

        reset(receiptPort, taskService);
        when(receiptPort.prepare(issued.envelope(), issued.decision()))
                .thenReturn(preparedReceipt("request-replay-1"));
        when(receiptPort.beginEffect(issued.envelope(), issued.decision()))
                .thenReturn(new CanonicalCommandReceiptPort.EffectPermit(
                        CanonicalCommandReceiptPort.BeginEffectDisposition.RESULT_RECORDED,
                        recorded(
                                "request-replay-1",
                                "attempt-race-1",
                                "BUSINESS_TASK:business-task-race")));

        BusinessAgentTaskCreateCommandCoordinator.RecordedReplay beginReplay = assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.RecordedReplay.class,
                coordinator.execute(prepared, issued.envelope(), issued.decision()));
        assertEquals("business-task-race", beginReplay.reference().taskId());
        verifyNoInteractions(taskService);
        verify(receiptPort, never()).recordResult(any(), any(), any(), any());
        verify(receiptPort, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void startedAndAmbiguousStatesNeverExecuteFreshEffect() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-state-1");

        for (CanonicalCommandReceiptPort.ReceiptState state : List.of(
                CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS)) {
            reset(receiptPort, taskService);
            when(receiptPort.prepare(issued.envelope(), issued.decision()))
                    .thenReturn(new CanonicalCommandReceiptPort.PrepareResult(
                            CanonicalCommandReceiptPort.PrepareDisposition.EXACT_REPLAY,
                            snapshot("request-state-1", state, "attempt-state", null, null)));
            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    prepared, issued.envelope(), issued.decision()));
            verify(receiptPort, never()).beginEffect(any(), any());
            verifyNoInteractions(taskService);
        }

        for (CanonicalCommandReceiptPort.BeginEffectDisposition disposition : List.of(
                CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED,
                CanonicalCommandReceiptPort.BeginEffectDisposition.AMBIGUOUS)) {
            reset(receiptPort, taskService);
            CanonicalCommandReceiptPort.ReceiptState state = disposition
                    == CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED
                    ? CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED
                    : CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS;
            when(receiptPort.prepare(issued.envelope(), issued.decision()))
                    .thenReturn(preparedReceipt("request-state-1"));
            when(receiptPort.beginEffect(issued.envelope(), issued.decision()))
                    .thenReturn(new CanonicalCommandReceiptPort.EffectPermit(
                            disposition,
                            snapshot("request-state-1", state, "attempt-state", null, null)));
            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    prepared, issued.envelope(), issued.decision()));
            verifyNoInteractions(taskService);
            verify(receiptPort, never()).recordResult(any(), any(), any(), any());
            verify(receiptPort, never()).markAmbiguous(any(), any(), any());
        }
    }

    @Test
    void exactReplayAfterSuccessDoesNotExecuteSecondFreshEffect() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-once-1");
        CreatedBusinessAgentTaskDTO freshTask = exactTask(plan);

        when(receiptPort.prepare(issued.envelope(), issued.decision()))
                .thenReturn(
                        preparedReceipt("request-once-1"),
                        new CanonicalCommandReceiptPort.PrepareResult(
                                CanonicalCommandReceiptPort.PrepareDisposition.EXACT_REPLAY,
                                recorded(
                                        "request-once-1",
                                        "attempt-once-1",
                                        "BUSINESS_TASK:business-task-1")));
        when(receiptPort.beginEffect(issued.envelope(), issued.decision()))
                .thenReturn(permitted("request-once-1", "attempt-once-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenReturn(freshTask);
        when(receiptPort.recordResult(any(), any(), any(), any()))
                .thenReturn(recorded(
                        "request-once-1",
                        "attempt-once-1",
                        "BUSINESS_TASK:business-task-1"));

        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.Executed.class,
                coordinator.execute(prepared, issued.envelope(), issued.decision()));
        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.RecordedReplay.class,
                coordinator.execute(prepared, issued.envelope(), issued.decision()));

        verify(taskService).executeFreshCreatePlan(prepared);
        verify(receiptPort).beginEffect(issued.envelope(), issued.decision());
        verify(receiptPort).recordResult(any(), any(), any(), any());
    }

    @Test
    void freshFailureMarksAttemptAmbiguousAndPreservesOriginalFailure() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-failure-1");
        IllegalStateException failure = new IllegalStateException("provider failed safely");

        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-failure-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted("request-failure-1", "attempt-failure-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenThrow(failure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(prepared, issued.envelope(), issued.decision()));

        assertSame(failure, actual);
        verify(receiptPort).markAmbiguous(
                "request-failure-1",
                "attempt-failure-1",
                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN);
        verify(receiptPort, never()).recordResult(any(), any(), any(), any());
    }

    @Test
    void everyPredictableResultDriftFailsClosedAfterPermitAndMarksAmbiguous() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        List<Consumer<CreatedBusinessAgentTaskDTO>> drifts = List.of(
                task -> task.setTaskId(null),
                task -> task.setTenantId("tenant-drift"),
                task -> task.setNavigatorEffectiveUserId("actor-drift"),
                task -> task.setClientAppId("app-drift"),
                task -> task.setUpstreamUserId("upstream-drift"),
                task -> task.setSessionId("session-drift"),
                task -> task.setAgentId("agent-drift"),
                task -> task.setSkillId("skill-drift"),
                task -> task.setWorkerPoolId("route-drift"),
                task -> task.setDirectoryId("directory-drift"),
                task -> task.setModelConfigId("model-config-drift"),
                task -> task.setModel("model-drift"),
                task -> task.setRequestedModelConfigId("requested-model-drift"),
                task -> task.setRequestedModelVariant("variant-drift"),
                task -> task.setStatus("RUNNING"),
                task -> task.setWorkerId("worker-drift"),
                task -> task.setWorkerProviderType("provider-drift"),
                task -> task.setWorkerTaskId(null),
                task -> task.setContextId("context-drift"),
                task -> task.setTaskScopedToken(null));

        for (int index = 0; index < drifts.size(); index++) {
            reset(receiptPort, taskService);
            String requestId = "request-result-drift-" + index;
            String attemptId = "attempt-result-drift-" + index;
            BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
            Issued issued = issue(prepared, requestId);
            CreatedBusinessAgentTaskDTO drifted = exactTask(plan);
            drifts.get(index).accept(drifted);
            when(receiptPort.prepare(any(), any())).thenReturn(preparedReceipt(requestId));
            when(receiptPort.beginEffect(any(), any()))
                    .thenReturn(permitted(requestId, attemptId));
            when(taskService.executeFreshCreatePlan(prepared)).thenReturn(drifted);

            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    prepared, issued.envelope(), issued.decision()));

            verify(receiptPort).markAmbiguous(
                    requestId,
                    attemptId,
                    BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN);
            verify(receiptPort, never()).recordResult(any(), any(), any(), any());
        }
    }

    @Test
    void recordFailureOrConflictMarksAmbiguousAndMarkFailureIsSuppressed() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-record-failure-1");
        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-record-failure-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted("request-record-failure-1", "attempt-record-failure-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenReturn(exactTask(plan));
        IllegalStateException recordFailure = new IllegalStateException("record failed safely");
        IllegalStateException markFailure = new IllegalStateException("mark failed safely");
        when(receiptPort.recordResult(any(), any(), any(), any())).thenThrow(recordFailure);
        when(receiptPort.markAmbiguous(any(), any(), any())).thenThrow(markFailure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(prepared, issued.envelope(), issued.decision()));

        assertSame(recordFailure, actual);
        assertEquals(List.of(markFailure), List.of(actual.getSuppressed()));

        reset(receiptPort, taskService);
        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-record-failure-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted("request-record-failure-1", "attempt-record-failure-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenReturn(exactTask(plan));
        when(receiptPort.recordResult(any(), any(), any(), any()))
                .thenReturn(recorded(
                        "request-record-failure-1",
                        "attempt-record-failure-1",
                        "BUSINESS_TASK:wrong-task"));

        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                prepared, issued.envelope(), issued.decision()));
        verify(receiptPort).markAmbiguous(
                "request-record-failure-1",
                "attempt-record-failure-1",
                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN);
    }

    @Test
    void invalidPermitAndRecordedReferenceFailClosedWithoutFreshEffect() {
        BusinessAgentTaskCreatePlan plan = plan(true);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-invalid-1");

        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-invalid-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted("request-invalid-1", null));
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                prepared, issued.envelope(), issued.decision()));
        verifyNoInteractions(taskService);
        verify(receiptPort, never()).markAmbiguous(any(), any(), any());

        for (String reference : List.of(
                "TASK:wrong-prefix",
                "BUSINESS_TASK:bad\ncontrol",
                "BUSINESS_TASK:" + "x".repeat(321))) {
            reset(receiptPort, taskService);
            when(receiptPort.prepare(any(), any()))
                    .thenReturn(new CanonicalCommandReceiptPort.PrepareResult(
                            CanonicalCommandReceiptPort.PrepareDisposition.EXACT_REPLAY,
                            recorded("request-invalid-1", "attempt-invalid-1", reference)));
            assertThrows(IllegalStateException.class, () -> coordinator.execute(
                    prepared, issued.envelope(), issued.decision()));
            verifyNoInteractions(taskService);
        }
    }

    @Test
    void noLauncherPlanRecordsFreshTaskWithNoProviderProjection() {
        BusinessAgentTaskCreatePlan plan = plan(false);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-no-launcher-1");
        assertEquals(null, issued.envelope().binding().target().physicalWorkerId());
        assertEquals(null, issued.envelope().binding().target().providerType());
        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-no-launcher-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted("request-no-launcher-1", "attempt-no-launcher-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenReturn(exactTask(plan));
        when(receiptPort.recordResult(any(), any(), any(), any()))
                .thenReturn(recorded(
                        "request-no-launcher-1",
                        "attempt-no-launcher-1",
                        "BUSINESS_TASK:business-task-1"));

        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.Executed.class,
                coordinator.execute(prepared, issued.envelope(), issued.decision()));
        verify(receiptPort, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void unicodeWhitespaceVariantMatchesExistingNullPersistenceSemantics() {
        BusinessAgentTaskCreatePlan plan = plan(true, "\u2003", null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(plan);
        Issued issued = issue(prepared, "request-unicode-variant-1");
        CreatedBusinessAgentTaskDTO freshTask = exactTask(plan);
        freshTask.setRequestedModelVariant(null);
        when(receiptPort.prepare(any(), any()))
                .thenReturn(preparedReceipt("request-unicode-variant-1"));
        when(receiptPort.beginEffect(any(), any()))
                .thenReturn(permitted(
                        "request-unicode-variant-1", "attempt-unicode-variant-1"));
        when(taskService.executeFreshCreatePlan(prepared)).thenReturn(freshTask);
        when(receiptPort.recordResult(any(), any(), any(), any()))
                .thenReturn(recorded(
                        "request-unicode-variant-1",
                        "attempt-unicode-variant-1",
                        "BUSINESS_TASK:business-task-1"));

        assertInstanceOf(
                BusinessAgentTaskCreateCommandCoordinator.Executed.class,
                coordinator.execute(prepared, issued.envelope(), issued.decision()));
        verify(receiptPort, never()).markAmbiguous(any(), any(), any());
    }

    private BusinessAgentTaskPreparedFreshCreate prepared(BusinessAgentTaskCreatePlan plan) {
        return prepared(plan, null);
    }

    private BusinessAgentTaskPreparedFreshCreate prepared(
            BusinessAgentTaskCreatePlan plan,
            String rawRequestedContextId) {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId("app-1");
        form.setSessionId("session-1");
        form.setContextId(rawRequestedContextId);
        form.setUpstreamUserId("upstream-user-1");
        form.setAgentId("agent-1");
        form.setDirectoryId("directory-1");
        form.setRequestedModelConfigId("requested-model-1");
        form.setModelVariant(plan.inputBinding().requestedModelVariant());
        form.setClientContextJson("client-context-must-not-escape");
        return new BusinessAgentTaskPreparedFreshCreate(
                plan, BusinessAgentTaskCreateInput.snapshot(form));
    }

    private String receiptScope(BusinessAgentTaskPreparedFreshCreate prepared) {
        return BusinessAgentTaskCreateCommandCoordinator.PlanBinding
                .from(prepared)
                .effect()
                .effectScopeReference();
    }

    private BusinessAgentTaskCreatePlan withResolvedContext(
            BusinessAgentTaskCreatePlan plan,
            String contextId) {
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        return copyPlan(
                new BusinessAgentTaskCreatePlan.Identity(
                        identity.tenantId(),
                        identity.actorUserId(),
                        identity.clientAppId(),
                        identity.upstreamSystemId(),
                        identity.upstreamUserId(),
                        identity.sessionId(),
                        contextId),
                plan.agentRoute(),
                plan.modelTarget(),
                plan.workspaceTarget(),
                plan.inputBinding());
    }

    private BusinessAgentTaskCreatePlan withActorUserId(
            BusinessAgentTaskCreatePlan plan,
            String actorUserId) {
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        return copyPlan(
                new BusinessAgentTaskCreatePlan.Identity(
                        identity.tenantId(),
                        actorUserId,
                        identity.clientAppId(),
                        identity.upstreamSystemId(),
                        identity.upstreamUserId(),
                        identity.sessionId(),
                        identity.contextId()),
                plan.agentRoute(),
                plan.modelTarget(),
                plan.workspaceTarget(),
                plan.inputBinding());
    }

    private BusinessAgentTaskCreatePlan withAgentId(
            BusinessAgentTaskCreatePlan plan,
            String agentId) {
        BusinessAgentTaskCreatePlan.AgentRoute route = plan.agentRoute();
        BusinessAgentTaskCreatePlan.AgentRoute drifted =
                new BusinessAgentTaskCreatePlan.AgentRoute(
                        agentId,
                        route.agentOwnerType(),
                        route.agentOwnerId(),
                        route.agentClientAppId(),
                        route.agentSource(),
                        route.skillId(),
                        route.skillName(),
                        route.internalWorkerRouteId(),
                        route.workerPoolId(),
                        route.workerPoolOwnerType(),
                        route.workerPoolOwnerId(),
                        route.workerPoolSource(),
                        route.workerBackend(),
                        route.agentPhysicalWorkerId(),
                        route.physicalWorkerOwnerType(),
                        route.physicalWorkerOwnerId(),
                        route.physicalWorkerSource(),
                        route.launchPhysicalWorkerId(),
                        route.selectedWorkerId(),
                        route.launcherType(),
                        route.expectedProviderType());
        return copyPlan(
                plan.identity(),
                drifted,
                plan.modelTarget(),
                plan.workspaceTarget(),
                plan.inputBinding());
    }

    private BusinessAgentTaskCreatePlan withModelName(
            BusinessAgentTaskCreatePlan plan,
            String modelName) {
        BusinessAgentTaskCreatePlan.ModelTarget model = plan.modelTarget();
        BusinessAgentTaskCreatePlan.ModelTarget drifted =
                new BusinessAgentTaskCreatePlan.ModelTarget(
                        model.modelConfigId(),
                        modelName,
                        model.visionModelConfigId(),
                        model.resolvedRequestedModelConfigId(),
                        model.resolvedRequestedModelVariant(),
                        model.category(),
                        model.modelNameSource(),
                        model.workerBackend(),
                        model.source());
        return copyPlan(
                plan.identity(),
                plan.agentRoute(),
                drifted,
                plan.workspaceTarget(),
                plan.inputBinding());
    }

    private BusinessAgentTaskCreatePlan withWorkdir(
            BusinessAgentTaskCreatePlan plan,
            String workdir) {
        BusinessAgentTaskCreatePlan.WorkspaceTarget workspace = plan.workspaceTarget();
        BusinessAgentTaskCreatePlan.WorkspaceTarget drifted =
                new BusinessAgentTaskCreatePlan.WorkspaceTarget(
                        workspace.directoryId(),
                        workspace.physicalWorkerId(),
                        workspace.workspaceScope(),
                        workspace.resolverType(),
                        workdir,
                        workspace.allowedDirs(),
                        workspace.readOnly(),
                        workspace.quotaPolicyDigest(),
                        workspace.retentionPolicyDigest(),
                        workspace.concurrencyPolicyDigest(),
                        workspace.source());
        return copyPlan(
                plan.identity(),
                plan.agentRoute(),
                plan.modelTarget(),
                drifted,
                plan.inputBinding());
    }

    private BusinessAgentTaskCreatePlan withAllowedTools(
            BusinessAgentTaskCreatePlan plan,
            List<String> allowedTools) {
        BusinessAgentTaskCreatePlan.InputBinding input = plan.inputBinding();
        BusinessAgentTaskCreatePlan.InputBinding drifted =
                new BusinessAgentTaskCreatePlan.InputBinding(
                        input.requestedModelConfigIdRaw(),
                        input.requestedModelVariant(),
                        input.requestedDirectoryId(),
                        allowedTools,
                        input.clientContextDigest());
        return copyPlan(
                plan.identity(),
                plan.agentRoute(),
                plan.modelTarget(),
                plan.workspaceTarget(),
                drifted);
    }

    private BusinessAgentTaskCreatePlan copyPlan(
            BusinessAgentTaskCreatePlan.Identity identity,
            BusinessAgentTaskCreatePlan.AgentRoute route,
            BusinessAgentTaskCreatePlan.ModelTarget model,
            BusinessAgentTaskCreatePlan.WorkspaceTarget workspace,
            BusinessAgentTaskCreatePlan.InputBinding input) {
        return new BusinessAgentTaskCreatePlan(identity, route, model, workspace, input, null);
    }

    private BusinessAgentTaskCreatePlan plan(boolean launcherPresent) {
        return plan(launcherPresent, " variant-1 ", "variant-1");
    }

    private BusinessAgentTaskCreatePlan plan(
            boolean launcherPresent,
            String requestedModelVariant,
            String resolvedRequestedModelVariant) {
        return new BusinessAgentTaskCreatePlan(
                new BusinessAgentTaskCreatePlan.Identity(
                        "tenant-1",
                        "actor-1",
                        "app-1",
                        "upstream-system-1",
                        "upstream-user-1",
                        "session-1",
                        "context-1"),
                new BusinessAgentTaskCreatePlan.AgentRoute(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "AGENT:CLIENT_APP",
                        "skill-1",
                        "skill-1",
                        "pool-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        launcherPresent ? "worker-1" : null,
                        launcherPresent ? ResourceOwnerType.UPSTREAM_SYSTEM : null,
                        launcherPresent ? "upstream-system-1" : null,
                        launcherPresent ? "BIZ_WORKER_IDENTITY" : null,
                        launcherPresent ? "worker-1" : null,
                        launcherPresent ? "worker-1" : null,
                        launcherPresent ? "WORKER:LANGGRAPH_BIZ" : null,
                        "langgraph-biz-worker"),
                new BusinessAgentTaskCreatePlan.ModelTarget(
                        "model-config-1",
                        "qwen-plus",
                        null,
                        "requested-model-1",
                        resolvedRequestedModelVariant,
                        LlmModelCategory.GENERAL,
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT"),
                new BusinessAgentTaskCreatePlan.WorkspaceTarget(
                        "directory-1",
                        launcherPresent ? "worker-1" : null,
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/workspace/app",
                        List.of("/workspace"),
                        false,
                        "quota-digest",
                        "retention-digest",
                        "concurrency-digest",
                        "WORKING_DIRECTORY:USER_PRIVATE"),
                new BusinessAgentTaskCreatePlan.InputBinding(
                        "requested-model-1",
                        requestedModelVariant,
                        "directory-1",
                        List.of("read_file"),
                        "client-context-digest"),
                null);
    }

    private CreatedBusinessAgentTaskDTO exactTask(BusinessAgentTaskCreatePlan plan) {
        CreatedBusinessAgentTaskDTO task = new CreatedBusinessAgentTaskDTO();
        task.setTaskId("business-task-1");
        task.setSessionId(plan.identity().sessionId());
        task.setContextId(plan.identity().contextId());
        task.setTenantId(plan.identity().tenantId());
        task.setClientAppId(plan.identity().clientAppId());
        task.setUpstreamUserId(plan.identity().upstreamUserId());
        task.setNavigatorEffectiveUserId(plan.identity().actorUserId());
        task.setAgentId(plan.agentRoute().agentId());
        task.setSkillId(plan.agentRoute().skillId());
        task.setWorkerPoolId(plan.agentRoute().internalWorkerRouteId());
        task.setDirectoryId(plan.workspaceTarget().directoryId());
        task.setModelConfigId(plan.modelTarget().modelConfigId());
        task.setRequestedModelConfigId(plan.inputBinding().requestedModelConfigIdRaw());
        task.setModel(plan.modelTarget().modelName());
        task.setRequestedModelVariant(plan.inputBinding().requestedModelVariant().trim());
        task.setStatus(BusinessAgentTaskService.STATUS_CREATED);
        if (plan.agentRoute().launcherType() != null) {
            task.setWorkerTaskId("worker-task-1");
            task.setWorkerSessionId("worker-session-1");
            task.setWorkerId(" " + plan.agentRoute().selectedWorkerId() + " ");
            task.setWorkerProviderType(plan.agentRoute().expectedProviderType());
        }
        task.setTaskScopedToken(TASK_TOKEN);
        return task;
    }

    private Issued issue(BusinessAgentTaskPreparedFreshCreate prepared, String requestId) {
        BusinessAgentTaskCreateCommandCoordinator.PlanBinding planBinding =
                BusinessAgentTaskCreateCommandCoordinator.PlanBinding.from(prepared);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.CREATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                                "NAVIGATOR_BUSINESS_AGENT",
                                "/api/v1/business-agent/tasks"),
                        new CanonicalCommandEnvelope.Request(requestId, requestId, requestId),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                AuthorizationCredentialLane.NAVIGATOR_JWT,
                                "actor-fingerprint",
                                null),
                        planBinding.ownership(),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision.ServerAuthority authority =
                new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "test-policy",
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(5));
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());
        return new Issued(envelope, decision);
    }

    private CanonicalCommandReceiptPort.PrepareResult preparedReceipt(String requestId) {
        return new CanonicalCommandReceiptPort.PrepareResult(
                CanonicalCommandReceiptPort.PrepareDisposition.CREATED,
                snapshot(
                        requestId,
                        CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                        null,
                        null,
                        null));
    }

    private CanonicalCommandReceiptPort.EffectPermit permitted(
            String requestId, String attemptId) {
        return new CanonicalCommandReceiptPort.EffectPermit(
                CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                snapshot(
                        requestId,
                        CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                        attemptId,
                        null,
                        null));
    }

    private CanonicalCommandReceiptPort.ReceiptSnapshot recorded(
            String requestId, String attemptId, String reference) {
        return snapshot(
                requestId,
                CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                attemptId,
                reference,
                BusinessAgentTaskCreateCommandCoordinator.BUSINESS_TASK_CREATED);
    }

    private CanonicalCommandReceiptPort.ReceiptSnapshot snapshot(
            String requestId,
            CanonicalCommandReceiptPort.ReceiptState state,
            String attemptId,
            String reference,
            String safeCode) {
        return new CanonicalCommandReceiptPort.ReceiptSnapshot(
                "receipt-" + requestId,
                requestId,
                state,
                attemptId,
                reference,
                safeCode,
                "decision-1",
                NOW,
                NOW,
                NOW.plusSeconds(300),
                LOCAL_NOW,
                state == CanonicalCommandReceiptPort.ReceiptState.PREPARED ? null : LOCAL_NOW,
                state == CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED ? LOCAL_NOW : null,
                state == CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS ? LOCAL_NOW : null,
                1L);
    }

    private record Issued(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }
}

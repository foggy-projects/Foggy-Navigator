package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupRepairEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalTombstoneEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupRepairRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupCompletenessPort;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupRepairPort.TerminalCleanupRepairAssessmentCommand;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupRepairPort.TerminalCleanupRepairCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TerminalCleanupRepairServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String WORKER_ID = "worker-1";
    private static final String REQUEST_ID = "repair-request-1";

    @Test
    void quarantinedPreEffectTerminalRepairsWithoutProviderWork() throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isTrue();
        assertThat(result.terminalTombstonePresent()).isTrue();
        assertThat(result.cleanupComplete()).isFalse();
        assertThat(result.safeReasonCode())
                .isEqualTo("TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        ArgumentCaptor<TerminalCommitCommand> command =
                ArgumentCaptor.forClass(TerminalCommitCommand.class);
        verify(fixture.terminalCommit).commit(command.capture());
        assertThat(command.getValue().tombstoneContext().providerTaskId()).isNull();
        assertThat(command.getValue().tombstoneContext().clientRequestId()).isNull();
        assertThat(command.getValue().outcome()).isEqualTo(TaskTerminalOutcome.FAILED);
        assertThat(command.getValue().source())
                .isEqualTo(TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION);
        verify(fixture.cleanup).resume(TASK_ID);
        ArgumentCaptor<TaskTerminalCleanupRepairEntity> receipt =
                ArgumentCaptor.forClass(TaskTerminalCleanupRepairEntity.class);
        verify(fixture.repairs).save(receipt.capture());
        assertThat(receipt.getValue().getClientRequestId()).isEqualTo(REQUEST_ID);
        assertThat(receipt.getValue().isCleanupComplete()).isFalse();
        // Repair must preserve historical quarantine rather than treating a
        // terminal cleanup as a new writer/proof admission.
        assertThat(fixture.snapshot.getAvailability()).isEqualTo(
                LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        assertThat(fixture.snapshot.getConflictState()).isEqualTo(
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
    }

    @Test
    void serverOwnedPreEffectAdmissionRejectionRepairsWithoutProviderWork()
            throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());
        fixture.snapshot.setTerminalSource(
                TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION.name());
        TaskLifecycleFact authority = TaskLifecycleFact.serverPreEffectAdmissionRejection(
                "fact-server-pre-effect", 7, binding());
        when(fixture.facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID)).thenReturn(List.of(factEntity(authority)));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isTrue();
        ArgumentCaptor<TerminalCommitCommand> command =
                ArgumentCaptor.forClass(TerminalCommitCommand.class);
        verify(fixture.terminalCommit).commit(command.capture());
        assertThat(command.getValue().tombstoneContext().providerTaskId()).isNull();
        assertThat(command.getValue().source()).isEqualTo(
                TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION);
        verify(fixture.cleanup).resume(TASK_ID);
    }

    @Test
    void sameRequestIdReplaysReceiptAndRefreshesCompletedStateWithoutRerunning() throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.COMPLETED.name());
        TaskTerminalCleanupRepairEntity receipt = receipt(TASK_ID, REQUEST_ID,
                true, true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        when(fixture.repairs.findByClientRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(receipt));
        when(fixture.repairs.findById(TASK_ID)).thenReturn(Optional.of(receipt));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isTrue();
        assertThat(result.terminalTombstonePresent()).isTrue();
        assertThat(result.cleanupComplete()).isTrue();
        assertThat(result.safeReasonCode())
                .isEqualTo("TERMINAL_CLEANUP_ALREADY_COMPLETE");
        verify(fixture.repairs).save(receipt);
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup, never()).resume(any());
    }

    @Test
    void freshRequestIdForPreviouslyRepairedTaskFailsClosed() throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.PENDING.name());
        TaskTerminalCleanupRepairEntity prior = receipt(TASK_ID, "repair-request-0",
                true, true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        when(fixture.repairs.findById(TASK_ID)).thenReturn(Optional.of(prior));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isFalse();
        assertThat(result.safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_REQUEST_ID_MISMATCH");
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup, never()).resume(any());
        verify(fixture.repairs, never()).save(any());
    }

    @Test
    void requestIdAlreadyBoundToOtherTaskFailsClosedBeforeLifecycleMutation()
            throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());
        when(fixture.repairs.findByClientRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(receipt("other-task", REQUEST_ID,
                        true, true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED")));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isFalse();
        assertThat(result.safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_REQUEST_ID_TASK_MISMATCH");
        verifyNoInteractions(fixture.snapshots, fixture.canonicalTasks,
                fixture.facts, fixture.tombstones, fixture.terminalCommit,
                fixture.cleanup);
    }

    @Test
    void mismatchedExpectedWorkerFailsClosedWithoutCommitOrCleanup()
            throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, "worker-other", REQUEST_ID));

        assertThat(result.repairAccepted()).isFalse();
        assertThat(result.safeReasonCode())
                .isEqualTo("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup, never()).resume(any());
        verify(fixture.repairs, never()).save(any());
    }

    @Test
    void missingOrNonExactTerminalAuthorityFailsClosed() throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());
        TaskLifecycleFact nonExact = new TaskLifecycleFact(
                "fact-non-exact", TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED,
                7, null, binding(), false);
        when(fixture.facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID)).thenReturn(List.of(factEntity(nonExact)));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isFalse();
        assertThat(result.safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_TERMINAL_AUTHORITY_REQUIRED");
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup, never()).resume(any());
    }

    @Test
    void nonEnforcedNonterminalAndNonterminalCanonicalStatusAllFailClosed()
            throws Exception {
        Fixture nonEnforced = fixture(false, TaskCleanupState.PENDING.name());
        nonEnforced.snapshot.setOwnershipMode(LifecycleOwnershipMode.LEGACY.name());
        assertThat(nonEnforced.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID)).safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_ENFORCED_OWNERSHIP_REQUIRED");

        Fixture nonterminal = fixture(false, TaskCleanupState.PENDING.name());
        nonterminal.snapshot.setCanonicalPhase(TaskCanonicalPhase.OPEN.name());
        assertThat(nonterminal.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID)).safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_CANONICAL_TERMINAL_REQUIRED");

        Fixture runningCanonicalTask = fixture(false, TaskCleanupState.PENDING.name());
        runningCanonicalTask.canonical.setStatus("RUNNING");
        assertThat(runningCanonicalTask.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID)).safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_CANONICAL_STATUS_REQUIRED");

        verify(nonEnforced.terminalCommit, never()).commit(any());
        verify(nonterminal.terminalCommit, never()).commit(any());
        verify(runningCanonicalTask.terminalCommit, never()).commit(any());
    }

    @Test
    void assessIsReadOnlyAndOnlyAllowsIncompleteTerminalCleanup() throws Exception {
        Fixture fixture = fixture(false, TaskCleanupState.PENDING.name());

        var assessment = fixture.service.assess(
                new TerminalCleanupRepairAssessmentCommand(TASK_ID, WORKER_ID));

        assertThat(assessment.repairEligible()).isTrue();
        assertThat(assessment.terminalTombstonePresent()).isFalse();
        assertThat(assessment.cleanupComplete()).isFalse();
        assertThat(assessment.safeReasonCode())
                .isEqualTo("NAVIGATOR_TERMINAL_REPUBLISH_READY");
        verify(fixture.snapshots, never()).findForUpdate(any());
        verifyNoInteractions(fixture.terminalCommit, fixture.cleanup,
                fixture.repairs);
    }

    @Test
    void completedTombstoneWithUnrevokedTokenRearmsOnlyLocalCleanup() throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.COMPLETED.name(), true);
        TaskTerminalCleanupPlanEntity tokenPlan = cleanupPlan(
                TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE,
                "NOT_APPLICABLE", "COMPLETED");
        TaskTerminalCleanupPlanEntity compatibilityPlan = cleanupPlan(
                TerminalCleanupParticipant.COMPATIBILITY_TASK_PROJECTION,
                "REQUIRED", "COMPLETED");
        when(fixture.cleanupPlans.findById(new TaskTerminalCleanupPlanId(
                TASK_ID, TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE.name())))
                .thenReturn(Optional.of(tokenPlan));

        var assessment = fixture.service.assess(
                new TerminalCleanupRepairAssessmentCommand(TASK_ID, WORKER_ID));
        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(assessment.repairEligible()).isTrue();
        assertThat(assessment.terminalTombstonePresent()).isTrue();
        assertThat(assessment.cleanupComplete()).isTrue();
        assertThat(assessment.safeReasonCode())
                .isEqualTo("NAVIGATOR_TERMINAL_REPUBLISH_READY");
        assertThat(result.repairAccepted()).isTrue();
        assertThat(result.terminalTombstonePresent()).isTrue();
        assertThat(result.cleanupComplete()).isFalse();
        assertThat(fixture.snapshot.getCleanupState())
                .isEqualTo(TaskCleanupState.PENDING.name());
        assertThat(tokenPlan.getApplicability()).isEqualTo("REQUIRED");
        assertThat(tokenPlan.getCheckpointState()).isEqualTo("PENDING");
        assertThat(compatibilityPlan.getApplicability()).isEqualTo("REQUIRED");
        assertThat(compatibilityPlan.getCheckpointState()).isEqualTo("COMPLETED");
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup).resume(TASK_ID);
        verify(fixture.cleanupPlans).save(tokenPlan);
        verify(fixture.cleanupPlans, never()).save(compatibilityPlan);
    }

    @Test
    void completedTombstoneWithRevokedOrAbsentTokenRemainsConverged() throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.COMPLETED.name(), false);

        var assessment = fixture.service.assess(
                new TerminalCleanupRepairAssessmentCommand(TASK_ID, WORKER_ID));

        assertThat(assessment.repairEligible()).isFalse();
        assertThat(assessment.safeReasonCode())
                .isEqualTo("TERMINAL_CLEANUP_ALREADY_COMPLETE");
        verifyNoInteractions(fixture.cleanupPlans, fixture.terminalCommit,
                fixture.cleanup, fixture.repairs);
    }

    @Test
    void completedTombstoneWithoutDurableTokenCompletenessFactFailsClosed()
            throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.COMPLETED.name(), false);
        when(fixture.completenessPort.assess(any())).thenReturn(List.of());

        var assessment = fixture.service.assess(
                new TerminalCleanupRepairAssessmentCommand(TASK_ID, WORKER_ID));

        assertThat(assessment.repairEligible()).isFalse();
        assertThat(assessment.safeReasonCode())
                .isEqualTo("LIFECYCLE_REPAIR_CLEANUP_COMPLETENESS_UNKNOWN");
        verifyNoInteractions(fixture.cleanupPlans, fixture.terminalCommit,
                fixture.cleanup, fixture.repairs);
    }

    @Test
    void sameRequestReplayWithUnknownCompletenessDoesNotClaimConvergence()
            throws Exception {
        Fixture fixture = fixture(true, TaskCleanupState.COMPLETED.name(), false);
        TaskTerminalCleanupRepairEntity receipt = receipt(TASK_ID, REQUEST_ID,
                true, true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        when(fixture.completenessPort.assess(any())).thenReturn(List.of());
        when(fixture.repairs.findByClientRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(receipt));
        when(fixture.repairs.findById(TASK_ID)).thenReturn(Optional.of(receipt));

        var result = fixture.service.repair(new TerminalCleanupRepairCommand(
                TASK_ID, WORKER_ID, REQUEST_ID));

        assertThat(result.repairAccepted()).isFalse();
        assertThat(result.safeReasonCode()).isEqualTo(
                "LIFECYCLE_REPAIR_CLEANUP_COMPLETENESS_UNKNOWN");
        verify(fixture.repairs, never()).save(receipt);
        verify(fixture.terminalCommit, never()).commit(any());
        verify(fixture.cleanup, never()).resume(any());
    }

    private Fixture fixture(boolean tombstonePresent, String cleanupState)
            throws Exception {
        return fixture(tombstonePresent, cleanupState, false);
    }

    private Fixture fixture(
            boolean tombstonePresent, String cleanupState, boolean physicalTokenIncomplete)
            throws Exception {
        TaskLifecycleSnapshotRepository snapshots = mock(TaskLifecycleSnapshotRepository.class);
        SessionTaskRepository canonicalTasks = mock(SessionTaskRepository.class);
        LifecycleFactRepository facts = mock(LifecycleFactRepository.class);
        TaskTerminalTombstoneRepository tombstones = mock(TaskTerminalTombstoneRepository.class);
        TaskTerminalCleanupPlanRepository cleanupPlans =
                mock(TaskTerminalCleanupPlanRepository.class);
        TaskTerminalCleanupRepairRepository repairs =
                mock(TaskTerminalCleanupRepairRepository.class);
        TaskTerminalCommitService terminalCommit = mock(TaskTerminalCommitService.class);
        TerminalCleanupHandler cleanup = mock(TerminalCleanupHandler.class);
        TerminalCleanupCompletenessPort completenessPort =
                mock(TerminalCleanupCompletenessPort.class);
        TaskLifecycleSnapshotEntity snapshot = snapshot(cleanupState);
        SessionTaskEntity canonical = canonical();
        TaskLifecycleFact authority = TaskLifecycleFact.exactPreEffectRejection(
                "fact-pre-effect", 7, binding());

        when(snapshots.findById(TASK_ID)).thenReturn(Optional.of(snapshot));
        when(snapshots.findForUpdate(TASK_ID)).thenReturn(Optional.of(snapshot));
        when(canonicalTasks.findByTaskId(TASK_ID)).thenReturn(Optional.of(canonical));
        when(facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID)).thenReturn(List.of(factEntity(authority)));
        when(tombstones.existsById(TASK_ID)).thenReturn(tombstonePresent);
        when(tombstones.findById(TASK_ID)).thenReturn(tombstonePresent
                ? Optional.of(tombstone()) : Optional.empty());
        when(completenessPort.supports(any())).thenReturn(true);
        when(completenessPort.assess(any())).thenReturn(List.of(
                new TerminalCleanupCompletenessPort.ParticipantCompleteness(
                        TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE.name(),
                        !physicalTokenIncomplete)));
        when(repairs.findByClientRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.empty());
        when(repairs.findById(TASK_ID)).thenReturn(Optional.empty());

        return new Fixture(new TerminalCleanupRepairService(snapshots,
                canonicalTasks, facts, tombstones, cleanupPlans, repairs,
                terminalCommit, cleanup, List.of(completenessPort),
                new ObjectMapper().findAndRegisterModules()), snapshots,
                canonicalTasks, facts, tombstones, cleanupPlans, repairs,
                terminalCommit, cleanup, completenessPort, snapshot, canonical);
    }

    private TaskLifecycleSnapshotEntity snapshot(String cleanupState) {
        TaskLifecycleSnapshotEntity value = new TaskLifecycleSnapshotEntity();
        value.setTaskId(TASK_ID);
        value.setSessionId("session-1");
        value.setPhysicalWorkerId(WORKER_ID);
        value.setStateGeneration("generation-1");
        value.setInstanceEpoch("epoch-1");
        value.setProviderTaskId(null);
        value.setDispatchId("dispatch-1");
        value.setOperationId("operation-1");
        value.setSafeBindingDigest("digest-1");
        value.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        value.setCanonicalPhase(TaskCanonicalPhase.TERMINAL.name());
        value.setTerminalOutcome(TaskTerminalOutcome.FAILED.name());
        value.setTerminalSource(TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION.name());
        value.setAvailability(LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        value.setConflictState(
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
        value.setCleanupState(cleanupState);
        value.setWriterGenerationId("writer-generation-1");
        return value;
    }

    private SessionTaskEntity canonical() {
        SessionTaskEntity value = new SessionTaskEntity();
        value.setTaskId(TASK_ID);
        value.setSessionId("session-1");
        value.setProviderType("codex-worker");
        value.setProviderTaskId(null);
        value.setUserId("user-1");
        value.setTenantId("tenant-1");
        value.setAgentId("agent-1");
        value.setStatus("FAILED");
        return value;
    }

    private TaskLifecycleBinding binding() {
        return new TaskLifecycleBinding("session-1", WORKER_ID,
                "generation-1", "epoch-1", LifecycleOwnershipMode.ENFORCED,
                "dispatch-1", "operation-1", "digest-1", null);
    }

    private LifecycleFactEntity factEntity(TaskLifecycleFact fact) throws Exception {
        LifecycleFactEntity entity = new LifecycleFactEntity();
        entity.setFactId(fact.factId());
        entity.setFactType(fact.type().name());
        entity.setAggregateType("TASK");
        entity.setAggregateId(TASK_ID);
        entity.setTaskId(TASK_ID);
        entity.setSessionId("session-1");
        entity.setOperationId("operation-1");
        entity.setPhysicalWorkerId(WORKER_ID);
        entity.setStateGeneration("generation-1");
        entity.setInstanceEpoch("epoch-1");
        entity.setProviderTaskId(null);
        entity.setDispatchId("dispatch-1");
        entity.setSafeBindingDigest("digest-1");
        entity.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        entity.setContentFreePayloadJson(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(fact));
        return entity;
    }

    private TaskTerminalCleanupRepairEntity receipt(
            String taskId,
            String clientRequestId,
            boolean accepted,
            boolean tombstonePresent,
            boolean cleanupComplete,
            String reason) {
        TaskTerminalCleanupRepairEntity value =
                new TaskTerminalCleanupRepairEntity();
        value.setTaskId(taskId);
        value.setClientRequestId(clientRequestId);
        value.setRepairAccepted(accepted);
        value.setTerminalTombstonePresent(tombstonePresent);
        value.setCleanupComplete(cleanupComplete);
        value.setSafeReasonCode(reason);
        return value;
    }

    private TaskTerminalCleanupPlanEntity cleanupPlan(
            TerminalCleanupParticipant participant,
            String applicability, String checkpointState) {
        TaskTerminalCleanupPlanEntity value = new TaskTerminalCleanupPlanEntity();
        value.setId(new TaskTerminalCleanupPlanId(TASK_ID, participant.name()));
        value.setApplicability(applicability);
        value.setCheckpointState(checkpointState);
        return value;
    }

    private TaskTerminalTombstoneEntity tombstone() {
        TaskTerminalTombstoneEntity value = new TaskTerminalTombstoneEntity();
        value.setTaskId(TASK_ID);
        value.setSessionId("session-1");
        value.setProviderType("codex-worker");
        value.setTenantId("tenant-1");
        value.setProviderTaskId(null);
        value.setProviderTaskUserId("user-1");
        value.setSourceAgentId("agent-1");
        value.setOperationId("operation-1");
        value.setClientRequestId(null);
        value.setTerminalOutcome(TaskTerminalOutcome.FAILED.name());
        return value;
    }

    private record Fixture(
            TerminalCleanupRepairService service,
            TaskLifecycleSnapshotRepository snapshots,
            SessionTaskRepository canonicalTasks,
            LifecycleFactRepository facts,
            TaskTerminalTombstoneRepository tombstones,
            TaskTerminalCleanupPlanRepository cleanupPlans,
            TaskTerminalCleanupRepairRepository repairs,
            TaskTerminalCommitService terminalCommit,
            TerminalCleanupHandler cleanup,
            TerminalCleanupCompletenessPort completenessPort,
            TaskLifecycleSnapshotEntity snapshot,
            SessionTaskEntity canonical) {
    }
}

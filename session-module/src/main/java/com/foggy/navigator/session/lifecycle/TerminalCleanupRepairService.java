package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupRepairEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupRepairRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupCompletenessPort;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupRepairPort;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuilds a missing terminal fence from facts Navigator has already made
 * durable. This service never invokes a provider, Worker, termination command,
 * retry, recovery, or task creation path.
 */
@Service
public class TerminalCleanupRepairService implements TerminalCleanupRepairPort {
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(
            "COMPLETED", "FAILED", "ABORTED", "CANCELLED", "REJECTED",
            "TIMED_OUT", "TIMEOUT");

    private final TaskLifecycleSnapshotRepository snapshots;
    private final SessionTaskRepository canonicalTasks;
    private final LifecycleFactRepository facts;
    private final TaskTerminalTombstoneRepository tombstones;
    private final TaskTerminalCleanupPlanRepository cleanupPlans;
    private final TaskTerminalCleanupRepairRepository repairs;
    private final TaskTerminalCommitService terminalCommit;
    private final TerminalCleanupHandler cleanup;
    private final List<TerminalCleanupCompletenessPort> completenessPorts;
    private final ObjectMapper objectMapper;

    public TerminalCleanupRepairService(
            TaskLifecycleSnapshotRepository snapshots,
            SessionTaskRepository canonicalTasks,
            LifecycleFactRepository facts,
            TaskTerminalTombstoneRepository tombstones,
            TaskTerminalCleanupPlanRepository cleanupPlans,
            TaskTerminalCleanupRepairRepository repairs,
            TaskTerminalCommitService terminalCommit,
            TerminalCleanupHandler cleanup,
            List<TerminalCleanupCompletenessPort> completenessPorts,
            ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.canonicalTasks = canonicalTasks;
        this.facts = facts;
        this.tombstones = tombstones;
        this.cleanupPlans = cleanupPlans;
        this.repairs = repairs;
        this.terminalCommit = terminalCommit;
        this.cleanup = cleanup;
        this.completenessPorts = List.copyOf(completenessPorts);
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public TerminalCleanupRepairAssessment assess(
            TerminalCleanupRepairAssessmentCommand command) {
        if (command == null || blank(command.taskId())) {
            return assessment(false, false, false,
                    "LIFECYCLE_REPAIR_TASK_ID_REQUIRED");
        }
        return evaluate(command.taskId(), command.expectedPhysicalWorkerId(), false)
                .assessment();
    }

    @Override
    @Transactional
    public TerminalCleanupRepairResult repair(
            TerminalCleanupRepairCommand command) {
        if (command == null || blank(command.taskId())) {
            return rejected("LIFECYCLE_REPAIR_TASK_ID_REQUIRED");
        }
        if (blank(command.clientRequestId())) {
            return rejected("LIFECYCLE_REPAIR_CLIENT_REQUEST_ID_REQUIRED");
        }

        TaskTerminalCleanupRepairEntity requestBound = repairs
                .findByClientRequestIdForUpdate(command.clientRequestId())
                .orElse(null);
        if (requestBound != null && !Objects.equals(
                requestBound.getTaskId(), command.taskId())) {
            return rejected("LIFECYCLE_REPAIR_REQUEST_ID_TASK_MISMATCH");
        }

        RepairState state = evaluate(command.taskId(),
                command.expectedPhysicalWorkerId(), true);
        TaskTerminalCleanupRepairEntity existingRepair = repairs
                .findById(command.taskId()).orElse(null);
        if (existingRepair != null) {
            if (!Objects.equals(existingRepair.getClientRequestId(),
                    command.clientRequestId())) {
                return rejected("LIFECYCLE_REPAIR_REQUEST_ID_MISMATCH");
            }
            return refresh(existingRepair, state.assessment());
        }
        if (!state.assessment().repairEligible()) {
            return rejected(state.assessment());
        }

        TerminalCleanupRepairResult accepted = new TerminalCleanupRepairResult(
                true, true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        repairs.save(repairReceipt(command.taskId(), command.clientRequestId(),
                accepted));
        if (!state.tombstonePresent()) {
            String terminalOperationId = Objects.equals(
                    state.authority().entity().getOperationId(),
                    state.snapshot().getDispatchId()) ? null
                    : state.authority().entity().getOperationId();
            terminalCommit.commit(new TerminalCommitCommand(
                    new TerminalTombstoneContext(
                            command.taskId(),
                            state.canonical().getSessionId(),
                            state.canonical().getProviderType(),
                            state.canonical().getTenantId(),
                            state.snapshot().getProviderTaskId(),
                            state.canonical().getUserId(),
                            state.canonical().getAgentId(),
                            terminalOperationId,
                            null),
                    state.authority().fact().factId(),
                    state.snapshot().getWriterGenerationId(),
                    state.terminal().outcome(),
                    state.terminal().source(),
                    new TerminalCleanupResources(false, false, false, false)));
        } else if (state.physicalTokenCleanupIncomplete()) {
            rearmCleanupForIncompleteToken(command.taskId(), state.snapshot());
        }
        afterCommit(() -> cleanup.resume(command.taskId()));
        return accepted;
    }

    private RepairState evaluate(
            String taskId, String expectedPhysicalWorkerId, boolean forUpdate) {
        TaskLifecycleSnapshotEntity snapshot = (forUpdate
                ? snapshots.findForUpdate(taskId) : snapshots.findById(taskId))
                .orElse(null);
        if (snapshot == null) {
            return unavailable("LIFECYCLE_TASK_NOT_ENROLLED");
        }
        if (!LifecycleOwnershipMode.ENFORCED.name().equals(
                snapshot.getOwnershipMode())) {
            return unavailable("LIFECYCLE_REPAIR_ENFORCED_OWNERSHIP_REQUIRED");
        }
        if (blank(expectedPhysicalWorkerId) || !Objects.equals(expectedPhysicalWorkerId,
                snapshot.getPhysicalWorkerId())) {
            return unavailable("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }
        TerminalState terminal = terminalState(snapshot).orElse(null);
        if (terminal == null) {
            return unavailable("LIFECYCLE_REPAIR_CANONICAL_TERMINAL_REQUIRED");
        }
        SessionTaskEntity canonical = canonicalTasks.findByTaskId(taskId).orElse(null);
        if (canonical == null || !TERMINAL_TASK_STATUSES.contains(
                canonical.getStatus())) {
            return unavailable("LIFECYCLE_REPAIR_CANONICAL_STATUS_REQUIRED");
        }
        if (!Objects.equals(snapshot.getSessionId(), canonical.getSessionId())
                || !Objects.equals(snapshot.getProviderTaskId(),
                canonical.getProviderTaskId())) {
            return unavailable("LIFECYCLE_REPAIR_CANONICAL_TASK_BINDING_REQUIRED");
        }
        AuthorityFact authority = exactTerminalAuthority(snapshot, terminal)
                .orElse(null);
        if (authority == null) {
            return unavailable("LIFECYCLE_REPAIR_TERMINAL_AUTHORITY_REQUIRED");
        }

        boolean tombstonePresent = tombstones.existsById(taskId);
        boolean cleanupComplete = TaskCleanupState.COMPLETED.name().equals(
                snapshot.getCleanupState());
        CleanupCompleteness completeness = tombstonePresent
                ? cleanupCompleteness(taskId)
                : CleanupCompleteness.notRequired();
        if (tombstonePresent && cleanupComplete && !completeness.known()) {
            return unavailable("LIFECYCLE_REPAIR_CLEANUP_COMPLETENESS_UNKNOWN");
        }
        boolean repairEligible = !tombstonePresent || !cleanupComplete
                || completeness.physicalTokenCleanupIncomplete();
        return new RepairState(assessment(repairEligible, tombstonePresent,
                cleanupComplete, repairEligible
                        ? "NAVIGATOR_TERMINAL_REPUBLISH_READY"
                        : "TERMINAL_CLEANUP_ALREADY_COMPLETE"), snapshot,
                canonical, terminal, authority, tombstonePresent,
                completeness.physicalTokenCleanupIncomplete());
    }

    /**
     * A completed lifecycle snapshot is not an authority to leave an active
     * task-scoped token behind. Re-arm only local cleanup checkpoints; the
     * handler uses the persisted tombstone context and never reaches a
     * provider or termination path.
     */
    private void rearmCleanupForIncompleteToken(
            String taskId, TaskLifecycleSnapshotEntity snapshot) {
        rearmCleanupCheckpoint(taskId,
                TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE);
        snapshot.setCleanupState(TaskCleanupState.PENDING.name());
        snapshots.save(snapshot);
    }

    private void rearmCleanupCheckpoint(
            String taskId, TerminalCleanupParticipant participant) {
        TaskTerminalCleanupPlanId id = new TaskTerminalCleanupPlanId(
                taskId, participant.name());
        TaskTerminalCleanupPlanEntity plan = cleanupPlans.findById(id)
                .orElseGet(() -> {
                    TaskTerminalCleanupPlanEntity created =
                            new TaskTerminalCleanupPlanEntity();
                    created.setId(id);
                    return created;
                });
        plan.setApplicability(CleanupApplicability.REQUIRED.name());
        plan.setNotApplicableReason(null);
        plan.setCheckpointState("PENDING");
        plan.setCheckpointFactId(null);
        cleanupPlans.save(plan);
    }

    /**
     * A task-token row can remain after a completed lifecycle snapshot. The
     * business module supplies only its durable completeness fact through the
     * SPI; session-module never reads token persistence directly. Absence of
     * a supporting fact is deliberately unknown rather than a no-action.
     */
    private CleanupCompleteness cleanupCompleteness(String taskId) {
        TerminalCleanupContext context = tombstones.findById(taskId)
                .map(this::cleanupContext)
                .orElse(null);
        if (context == null) {
            return CleanupCompleteness.unknown();
        }
        boolean reported = false;
        boolean incomplete = false;
        for (TerminalCleanupCompletenessPort port : completenessPorts) {
            if (!port.supports(context)) {
                continue;
            }
            List<TerminalCleanupCompletenessPort.ParticipantCompleteness> values =
                    port.assess(context);
            if (values == null) {
                return CleanupCompleteness.unknown();
            }
            for (TerminalCleanupCompletenessPort.ParticipantCompleteness value : values) {
                if (value == null) {
                    return CleanupCompleteness.unknown();
                }
                if (TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE.name()
                        .equals(value.participant())) {
                    reported = true;
                    incomplete |= !value.complete();
                }
            }
        }
        return reported ? CleanupCompleteness.known(incomplete)
                : CleanupCompleteness.unknown();
    }

    private TerminalCleanupContext cleanupContext(
            com.foggy.navigator.session.lifecycle.persistence.TaskTerminalTombstoneEntity
                    tombstone) {
        return new TerminalCleanupContext(
                tombstone.getTaskId(), tombstone.getSessionId(),
                tombstone.getProviderType(), tombstone.getTenantId(),
                tombstone.getProviderTaskId(), tombstone.getProviderTaskUserId(),
                tombstone.getSourceAgentId(), tombstone.getOperationId(),
                tombstone.getClientRequestId(), tombstone.getTerminalOutcome());
    }

    private Optional<TerminalState> terminalState(
            TaskLifecycleSnapshotEntity snapshot) {
        if (!TaskCanonicalPhase.TERMINAL.name().equals(snapshot.getCanonicalPhase())
                || blank(snapshot.getWriterGenerationId())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TerminalState(
                    TaskTerminalOutcome.valueOf(snapshot.getTerminalOutcome()),
                    TaskTerminalSource.valueOf(snapshot.getTerminalSource())));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    private Optional<AuthorityFact> exactTerminalAuthority(
            TaskLifecycleSnapshotEntity snapshot, TerminalState terminal) {
        List<LifecycleFactEntity> candidates = facts
                .findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                        "TASK", snapshot.getTaskId());
        for (LifecycleFactEntity entity : candidates) {
            if (!isTerminalAuthorityType(entity.getFactType())) {
                continue;
            }
            TaskLifecycleFact fact = parse(entity).orElse(null);
            if (fact != null
                    && fact.exactTerminalAuthority()
                    && exactDurableEnvelope(snapshot, entity, fact)
                    && exactSnapshotBinding(snapshot, fact.binding())
                    && authorityMatchesTerminal(snapshot, terminal, fact)) {
                return Optional.of(new AuthorityFact(entity, fact));
            }
        }
        return Optional.empty();
    }

    private Optional<TaskLifecycleFact> parse(LifecycleFactEntity entity) {
        try {
            return Optional.of(objectMapper.readValue(
                    entity.getContentFreePayloadJson(), TaskLifecycleFact.class));
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private boolean exactDurableEnvelope(
            TaskLifecycleSnapshotEntity snapshot,
            LifecycleFactEntity entity, TaskLifecycleFact fact) {
        TaskLifecycleBinding binding = fact.binding();
        return binding != null
                && Objects.equals(entity.getFactId(), fact.factId())
                && Objects.equals(entity.getFactType(), fact.type().name())
                && "TASK".equals(entity.getAggregateType())
                && Objects.equals(entity.getAggregateId(), snapshot.getTaskId())
                && Objects.equals(entity.getTaskId(), snapshot.getTaskId())
                && Objects.equals(entity.getSessionId(), binding.sessionId())
                && Objects.equals(entity.getPhysicalWorkerId(),
                binding.physicalWorkerId())
                && Objects.equals(entity.getStateGeneration(),
                binding.stateGeneration())
                && Objects.equals(entity.getInstanceEpoch(), binding.instanceEpoch())
                && Objects.equals(entity.getProviderTaskId(), binding.providerTaskId())
                && Objects.equals(entity.getDispatchId(), binding.dispatchId())
                && Objects.equals(entity.getOperationId(), binding.operationId())
                && Objects.equals(entity.getSafeBindingDigest(),
                binding.bindingDigest())
                && LifecycleOwnershipMode.ENFORCED.name().equals(
                entity.getOwnershipMode());
    }

    private boolean exactSnapshotBinding(
            TaskLifecycleSnapshotEntity snapshot, TaskLifecycleBinding binding) {
        return binding != null
                && Objects.equals(snapshot.getSessionId(), binding.sessionId())
                && Objects.equals(snapshot.getPhysicalWorkerId(),
                binding.physicalWorkerId())
                && Objects.equals(snapshot.getStateGeneration(),
                binding.stateGeneration())
                && Objects.equals(snapshot.getInstanceEpoch(), binding.instanceEpoch())
                && LifecycleOwnershipMode.ENFORCED == binding.ownershipMode()
                && Objects.equals(snapshot.getDispatchId(), binding.dispatchId())
                && Objects.equals(snapshot.getOperationId(), binding.operationId())
                && Objects.equals(snapshot.getSafeBindingDigest(),
                binding.bindingDigest())
                && Objects.equals(snapshot.getProviderTaskId(),
                binding.providerTaskId());
    }

    private boolean authorityMatchesTerminal(
            TaskLifecycleSnapshotEntity snapshot,
            TerminalState terminal,
            TaskLifecycleFact fact) {
        if (fact.type() == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED) {
            return terminal.source() == TaskTerminalSource.WORKER_EVIDENCE
                    && fact.terminalOutcome() == terminal.outcome()
                    && snapshot.getProviderTaskId() != null;
        }
        if (fact.type()
                == TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED) {
            return terminal.source()
                    == TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION
                    && terminal.outcome() == TaskTerminalOutcome.FAILED
                    && snapshot.getProviderTaskId() == null;
        }
        return fact.type() == TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED
                && terminal.source() == TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION
                && terminal.outcome() == TaskTerminalOutcome.FAILED
                && snapshot.getProviderTaskId() == null;
    }

    private static boolean isTerminalAuthorityType(String type) {
        return TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED.name()
                .equals(type)
                || TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED.name()
                .equals(type)
                || TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED.name()
                .equals(type);
    }

    private static TerminalCleanupRepairResult rejected(String code) {
        return new TerminalCleanupRepairResult(false, false, false, code);
    }

    private static TerminalCleanupRepairResult rejected(
            TerminalCleanupRepairAssessment assessment) {
        return new TerminalCleanupRepairResult(false,
                assessment.terminalTombstonePresent(),
                assessment.cleanupComplete(), assessment.safeReasonCode());
    }

    private static TerminalCleanupRepairAssessment assessment(
            boolean repairEligible,
            boolean terminalTombstonePresent,
            boolean cleanupComplete,
            String safeReasonCode) {
        return new TerminalCleanupRepairAssessment(repairEligible,
                terminalTombstonePresent, cleanupComplete, safeReasonCode);
    }

    private static RepairState unavailable(String safeReasonCode) {
        return new RepairState(assessment(false, false, false, safeReasonCode),
                null, null, null, null, false, false);
    }

    private static TerminalCleanupRepairResult from(
            TaskTerminalCleanupRepairEntity receipt) {
        return new TerminalCleanupRepairResult(receipt.isRepairAccepted(),
                receipt.isTerminalTombstonePresent(),
                receipt.isCleanupComplete(), receipt.getSafeReasonCode());
    }

    private static TaskTerminalCleanupRepairEntity repairReceipt(
            String taskId,
            String clientRequestId,
            TerminalCleanupRepairResult result) {
        TaskTerminalCleanupRepairEntity receipt =
                new TaskTerminalCleanupRepairEntity();
        receipt.setTaskId(taskId);
        receipt.setClientRequestId(clientRequestId);
        receipt.setRepairAccepted(result.repairAccepted());
        receipt.setTerminalTombstonePresent(result.terminalTombstonePresent());
        receipt.setCleanupComplete(result.cleanupComplete());
        receipt.setSafeReasonCode(result.safeReasonCode());
        return receipt;
    }

    /**
     * Replays are provider-free and never resume cleanup a second time.  They
     * do, however, refresh the receipt from the canonical snapshot, so a
     * later same-id read truthfully reports cleanup that an earlier scheduled
     * resume completed.
     */
    private TerminalCleanupRepairResult refresh(
            TaskTerminalCleanupRepairEntity receipt,
            TerminalCleanupRepairAssessment current) {
        boolean converged = current.terminalTombstonePresent()
                && current.cleanupComplete()
                && "TERMINAL_CLEANUP_ALREADY_COMPLETE".equals(
                current.safeReasonCode());
        boolean stillRepairing = current.repairEligible()
                && "NAVIGATOR_TERMINAL_REPUBLISH_READY".equals(
                current.safeReasonCode());
        if (!converged && !stillRepairing) {
            // A prior request receipt cannot turn missing durability evidence
            // into a successful terminal conclusion. Keep the historic receipt
            // intact and make this replay explicitly fail closed.
            return new TerminalCleanupRepairResult(false,
                    current.terminalTombstonePresent(),
                    current.cleanupComplete(), current.safeReasonCode());
        }
        receipt.setTerminalTombstonePresent(
                current.terminalTombstonePresent());
        receipt.setCleanupComplete(current.cleanupComplete());
        receipt.setSafeReasonCode(converged
                ? "TERMINAL_CLEANUP_ALREADY_COMPLETE"
                : "TERMINAL_CLEANUP_REPAIR_ACCEPTED");
        repairs.save(receipt);
        return from(receipt);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private record TerminalState(
            TaskTerminalOutcome outcome, TaskTerminalSource source) {
    }

    private record AuthorityFact(
            LifecycleFactEntity entity, TaskLifecycleFact fact) {
    }

    private record RepairState(
            TerminalCleanupRepairAssessment assessment,
            TaskLifecycleSnapshotEntity snapshot,
            SessionTaskEntity canonical,
            TerminalState terminal,
            AuthorityFact authority,
            boolean tombstonePresent,
            boolean physicalTokenCleanupIncomplete) {
    }

    private record CleanupCompleteness(
            boolean known,
            boolean physicalTokenCleanupIncomplete) {
        private static CleanupCompleteness notRequired() {
            return new CleanupCompleteness(true, false);
        }

        private static CleanupCompleteness unknown() {
            return new CleanupCompleteness(false, false);
        }

        private static CleanupCompleteness known(boolean incomplete) {
            return new CleanupCompleteness(true, incomplete);
        }
    }
}

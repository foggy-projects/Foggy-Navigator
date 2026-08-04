package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Production lifecycle owner ingress. Inventory establishes an exact immutable
 * task/Worker binding; normalized facts are validated against that binding
 * before the canonical reducer may commit a terminal fence.
 */
@Service
public class TaskLifecycleOwnerService {
    private static final String POLICY = "ARCH-001-MVP-A";
    private final LifecycleFactRepository facts;
    private final LifecycleEffectOutboxRepository outbox;
    private final TaskLifecycleSnapshotRepository snapshots;
    private final TaskTerminalTombstoneRepository tombstones;
    private final SessionTaskRepository canonicalTasks;
    private final TaskTerminalCommitService terminalCommit;
    private final TerminalCleanupHandler cleanup;
    private final ObjectMapper objectMapper;
    private final TaskLifecycleReducer reducer = new TaskLifecycleReducer();

    public TaskLifecycleOwnerService(
            LifecycleFactRepository facts,
            LifecycleEffectOutboxRepository outbox,
            TaskLifecycleSnapshotRepository snapshots,
            TaskTerminalTombstoneRepository tombstones,
            SessionTaskRepository canonicalTasks,
            TaskTerminalCommitService terminalCommit,
            TerminalCleanupHandler cleanup,
            ObjectMapper objectMapper) {
        this.facts = facts;
        this.outbox = outbox;
        this.snapshots = snapshots;
        this.tombstones = tombstones;
        this.canonicalTasks = canonicalTasks;
        this.terminalCommit = terminalCommit;
        this.cleanup = cleanup;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enrollInventoryTask(
            WorkerLifecycleIdentity identity,
            WorkerLifecycleTask workerTask,
            String writerGenerationId) {
        SessionTaskEntity canonical = canonicalTasks
                .findByTaskIdForUpdate(workerTask.navigatorTaskId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_CANONICAL_TASK_NOT_FOUND"));
        if (canonical.getProviderTaskId() != null
                && !canonical.getProviderTaskId().equals(workerTask.providerTaskId())) {
            throw new IllegalStateException("LIFECYCLE_PROVIDER_TASK_IDENTITY_MISMATCH");
        }
        TaskLifecycleBinding binding = new TaskLifecycleBinding(
                canonical.getSessionId(),
                identity.physicalWorkerId(),
                identity.stateGeneration(),
                identity.instanceEpoch(),
                workerTask.ownershipMode(),
                workerTask.initialDispatchId(),
                workerTask.initialDispatchId(),
                workerTask.safeBindingDigest(),
                workerTask.providerTaskId());
        TaskLifecycleSnapshotEntity current = snapshots
                .findForUpdate(workerTask.navigatorTaskId()).orElse(null);
        if (current != null) {
            requireExact(current, binding);
            if (!identity.instanceEpoch().equals(current.getInstanceEpoch())) {
                current.setInstanceEpoch(identity.instanceEpoch());
                snapshots.save(current);
            }
            return;
        }
        TaskLifecycleSnapshotEntity created = new TaskLifecycleSnapshotEntity();
        created.setTaskId(workerTask.navigatorTaskId());
        applyBinding(created, binding, workerTask.safeBindingDigestVersion());
        created.setCanonicalPhase(TaskCanonicalPhase.OPEN.name());
        created.setAvailability(LifecycleAvailability.READY.name());
        created.setConflictState(LifecycleConflictState.NONE.name());
        created.setCleanupState(TaskCleanupState.NOT_REQUIRED.name());
        created.setFactCursor(0L);
        created.setPolicyVersion(POLICY);
        created.setWriterGenerationId(workerTask.ownershipMode()
                == LifecycleOwnershipMode.ENFORCED
                ? required(writerGenerationId, "LIFECYCLE_WRITER_GENERATION_REQUIRED")
                : null);
        created.setSnapshotJson("{}");
        snapshots.save(created);
    }

    @Transactional
    public TaskLifecycleDecision ingestNormalizedBatch(
            String taskId, List<NormalizedLifecycleFact> normalizedFacts) {
        if (normalizedFacts == null || normalizedFacts.isEmpty()) {
            throw new IllegalArgumentException("LIFECYCLE_FACT_BATCH_REQUIRED");
        }
        if (normalizedFacts.size() > 50) {
            throw new IllegalArgumentException(
                    "LIFECYCLE_FACT_BATCH_LIMIT_EXCEEDED");
        }
        TaskLifecycleSnapshotEntity enrolled = snapshots.findForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_TASK_NOT_ENROLLED"));
        TaskLifecycleBinding expected = binding(enrolled);
        for (NormalizedLifecycleFact normalized : normalizedFacts) {
            if (!taskId.equals(normalized.taskId())) {
                throw new IllegalStateException("LIFECYCLE_FACT_TASK_MISMATCH");
            }
            TaskLifecycleFact fact = normalizedFact(normalized, expected);
            if (!facts.existsById(fact.factId())) {
                facts.save(factEntity(taskId, fact, normalized));
            }
            observeAuthorizedEffectResult(normalized, fact);
        }
        List<TaskLifecycleFact> aggregateFacts = facts
                .findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc("TASK", taskId)
                .stream().map(this::fact).toList();
        TaskLifecycleDecision decision = reducer.recompute(
                taskId, aggregateFacts, Set.of(), expected, POLICY);
        applyDecision(enrolled, decision);
        snapshots.save(enrolled);
        if (expected.ownershipMode() == LifecycleOwnershipMode.ENFORCED
                && decision.snapshot().canonicalTerminal()
                && decision.snapshot().conflictState() == LifecycleConflictState.NONE) {
            SessionTaskEntity canonical = canonicalTasks.findByTaskId(taskId)
                    .orElseThrow();
            TaskLifecycleFact authority = aggregateFacts.stream()
                    .filter(candidate -> candidate.type()
                            == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED
                            || candidate.type()
                            == TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED)
                    .filter(TaskLifecycleFact::exactTerminalAuthority)
                    .findFirst().orElseThrow();
            String terminalOperationId = facts.findById(
                            authority.factId())
                    .map(LifecycleFactEntity::getOperationId)
                    .filter(operation -> !operation.equals(
                            expected.dispatchId()))
                    .orElse(null);
            String clientRequestId = terminalOperationId == null
                    ? null : exactTerminationClientRequestId(
                            taskId, terminalOperationId,
                            facts.findById(authority.factId())
                                    .map(LifecycleFactEntity::getDispatchId)
                                    .orElseThrow());
            terminalCommit.commit(new TerminalCommitCommand(
                    new TerminalTombstoneContext(
                            taskId,
                            canonical.getSessionId(),
                            canonical.getProviderType(),
                            canonical.getTenantId(),
                            expected.providerTaskId(),
                            canonical.getUserId(),
                            canonical.getAgentId(),
                            terminalOperationId,
                            clientRequestId),
                    authority.factId(),
                    required(enrolled.getWriterGenerationId(),
                            "LIFECYCLE_WRITER_GENERATION_REQUIRED"),
                    decision.snapshot().terminalOutcome(),
                    decision.snapshot().terminalSource(),
                    new TerminalCleanupResources(
                            "codex-biz-worker".equals(canonical.getProviderType()),
                            !expected.operationId().equals(expected.dispatchId()),
                            !expected.operationId().equals(expected.dispatchId()),
                            "codex-biz-worker".equals(canonical.getProviderType())
                                    || "codex-worker".equals(canonical.getProviderType()))));
            afterCommit(() -> cleanup.resume(taskId));
        }
        return decision;
    }

    /**
     * Commits the terminal fence for a server-side provider-effect admission
     * denial after the relay has durably marked the canonical task FAILED.
     * This is intentionally not part of Worker normalized-fact ingress: the
     * Worker never accepted a task and no provider identity may be invented.
     */
    @Transactional
    public void closeServerPreEffectAdmissionRejection(
            ServerPreEffectAdmissionRejection command) {
        TaskLifecycleBinding expected = serverPreEffectBinding(command);
        SessionTaskEntity canonical = canonicalTasks.findByTaskIdForUpdate(
                        command.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_CANONICAL_TASK_NOT_FOUND"));
        if (!"FAILED".equals(canonical.getStatus())
                || canonical.getProviderTaskId() != null
                || !expected.sessionId().equals(canonical.getSessionId())
                || !expected.physicalWorkerId().equals(canonical.getWorkerId())) {
            throw new IllegalStateException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_CANONICAL_BINDING_REQUIRED");
        }

        List<LifecycleFactEntity> storedFacts = facts
                .findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                        "TASK", command.taskId());
        TaskLifecycleSnapshotEntity enrolled = snapshots
                .findForUpdate(command.taskId()).orElse(null);
        var existingTombstone = tombstones.findById(command.taskId()).orElse(null);
        String authorityFactId = serverPreEffectFactId(command.taskId());
        TaskLifecycleFact authority;
        if (enrolled == null) {
            if (!storedFacts.isEmpty() || existingTombstone != null) {
                throw new IllegalStateException(
                        "LIFECYCLE_SERVER_PRE_EFFECT_FACT_CONFLICT");
            }
            enrolled = newServerPreEffectSnapshot(command, expected);
            authority = TaskLifecycleFact.serverPreEffectAdmissionRejection(
                    authorityFactId, 1L, expected);
            LifecycleFactEntity entity = serverPreEffectFactEntity(
                    command, authority);
            facts.save(entity);
            storedFacts = List.of(entity);
        } else {
            requireExact(enrolled, expected);
            authority = requireOnlyServerPreEffectAuthority(
                    command, expected, storedFacts, authorityFactId);
            if (exactServerPreEffectTerminalSnapshot(enrolled, command)
                    && existingTombstone != null) {
                if (exactServerPreEffectTombstone(
                        existingTombstone, command, authorityFactId)) {
                    return;
                }
                throw new IllegalStateException(
                        "LIFECYCLE_SERVER_PRE_EFFECT_TOMBSTONE_CONFLICT");
            }
        }

        List<TaskLifecycleFact> aggregateFacts = storedFacts.stream()
                .map(this::fact).toList();
        TaskLifecycleDecision decision = reducer.recompute(
                command.taskId(), aggregateFacts,
                Set.of(LifecycleBlocker.WRITER_EXCLUSIVITY_LOST), expected,
                POLICY);
        if (!decision.snapshot().canonicalTerminal()
                || decision.snapshot().terminalOutcome() != TaskTerminalOutcome.FAILED
                || decision.snapshot().terminalSource()
                != TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION
                || decision.snapshot().dispatchState() != TaskDispatchState.REJECTED
                || decision.snapshot().executionObservation()
                != TaskExecutionObservation.NOT_STARTED) {
            throw new IllegalStateException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_REDUCTION_INVALID");
        }
        applyDecision(enrolled, decision);
        enrolled.setWriterGenerationId(command.writerGenerationId());
        snapshots.save(enrolled);
        terminalCommit.commit(new TerminalCommitCommand(
                new TerminalTombstoneContext(
                        command.taskId(),
                        canonical.getSessionId(),
                        canonical.getProviderType(),
                        canonical.getTenantId(),
                        null,
                        canonical.getUserId(),
                        canonical.getAgentId(),
                        null,
                        null),
                authority.factId(),
                command.writerGenerationId(),
                TaskTerminalOutcome.FAILED,
                TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION,
                new TerminalCleanupResources(false, false, false, false)));
        afterCommit(() -> cleanup.resume(command.taskId()));
    }

    private String exactTerminationClientRequestId(
            String taskId,
            String operationId,
            String dispatchId) {
        String prefix = "termination-intent:";
        List<String> requests = outbox
                .findByAggregateIdAndOperationId(taskId, operationId)
                .stream()
                .filter(effect -> "TERMINATION_REQUEST".equals(
                        effect.getEffectType()))
                .filter(effect -> dispatchId.equals(effect.getDispatchId()))
                .map(effect -> effect.getIdempotencyKey())
                .filter(key -> key != null && key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .distinct()
                .toList();
        if (requests.size() != 1) {
            throw new IllegalStateException(
                    "TERMINATION_RECEIPT_EXACT_BINDING_REQUIRED");
        }
        return requests.get(0);
    }

    private TaskLifecycleBinding serverPreEffectBinding(
            ServerPreEffectAdmissionRejection command) {
        if (command == null
                || command.workerIdentity() == null
                || !"JCS_SHA256_V1".equals(command.bindingDigestVersion())
                || command.safeReasonCode() == null
                || !command.safeReasonCode().matches("[A-Z][A-Z0-9_]{2,95}")) {
            throw new IllegalArgumentException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_COMMAND_INVALID");
        }
        required(command.taskId(),
                "LIFECYCLE_SERVER_PRE_EFFECT_TASK_REQUIRED");
        required(command.writerGenerationId(),
                "LIFECYCLE_SERVER_PRE_EFFECT_WRITER_REQUIRED");
        return new TaskLifecycleBinding(
                required(command.sessionId(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_SESSION_REQUIRED"),
                required(command.physicalWorkerId(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_WORKER_REQUIRED"),
                required(command.workerIdentity().stateGeneration(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_GENERATION_REQUIRED"),
                required(command.workerIdentity().instanceEpoch(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_EPOCH_REQUIRED"),
                LifecycleOwnershipMode.ENFORCED,
                required(command.dispatchId(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_DISPATCH_REQUIRED"),
                required(command.dispatchId(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_DISPATCH_REQUIRED"),
                required(command.bindingDigest(),
                        "LIFECYCLE_SERVER_PRE_EFFECT_DIGEST_REQUIRED"),
                null);
    }

    private boolean exactServerPreEffectTombstone(
            com.foggy.navigator.session.lifecycle.persistence
                    .TaskTerminalTombstoneEntity tombstone,
            ServerPreEffectAdmissionRejection command,
            String authorityFactId) {
        return command.taskId().equals(tombstone.getTaskId())
                && command.sessionId().equals(tombstone.getSessionId())
                && tombstone.getProviderTaskId() == null
                && TaskTerminalOutcome.FAILED.name().equals(
                tombstone.getTerminalOutcome())
                && TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION.name()
                .equals(tombstone.getTerminalSource())
                && authorityFactId.equals(tombstone.getTerminalFactId())
                && command.writerGenerationId().equals(
                tombstone.getWriterGenerationId());
    }

    private TaskLifecycleSnapshotEntity newServerPreEffectSnapshot(
            ServerPreEffectAdmissionRejection command,
            TaskLifecycleBinding binding) {
        TaskLifecycleSnapshotEntity snapshot = new TaskLifecycleSnapshotEntity();
        snapshot.setTaskId(command.taskId());
        applyBinding(snapshot, binding, command.bindingDigestVersion());
        snapshot.setCanonicalPhase(TaskCanonicalPhase.OPEN.name());
        snapshot.setAvailability(LifecycleAvailability
                .AUTHORITY_QUARANTINED.name());
        snapshot.setConflictState(LifecycleConflictState
                .LEGACY_WRITER_EXCLUSIVITY_LOST.name());
        snapshot.setCleanupState(TaskCleanupState.NOT_REQUIRED.name());
        snapshot.setFactCursor(0L);
        snapshot.setPolicyVersion(POLICY);
        snapshot.setWriterGenerationId(required(command.writerGenerationId(),
                "LIFECYCLE_SERVER_PRE_EFFECT_WRITER_GENERATION_REQUIRED"));
        snapshot.setSnapshotJson("{}");
        return snapshot;
    }

    private TaskLifecycleFact requireOnlyServerPreEffectAuthority(
            ServerPreEffectAdmissionRejection command,
            TaskLifecycleBinding expected,
            List<LifecycleFactEntity> storedFacts,
            String authorityFactId) {
        if (storedFacts.size() != 1) {
            throw new IllegalStateException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_FACT_CONFLICT");
        }
        LifecycleFactEntity entity = storedFacts.get(0);
        TaskLifecycleFact candidate = fact(entity);
        boolean exact = authorityFactId.equals(candidate.factId())
                && candidate.type()
                == TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED
                && candidate.exactTerminalAuthority()
                && expected.exactRuntimeMatch(candidate.binding())
                && entity.getSchemaVersion() == 1
                && "TASK".equals(entity.getAggregateType())
                && command.taskId().equals(entity.getAggregateId())
                && command.taskId().equals(entity.getTaskId())
                && expected.sessionId().equals(entity.getSessionId())
                && expected.physicalWorkerId().equals(
                entity.getPhysicalWorkerId())
                && expected.stateGeneration().equals(entity.getStateGeneration())
                && expected.instanceEpoch().equals(entity.getInstanceEpoch())
                && entity.getProviderTaskId() == null
                && expected.dispatchId().equals(entity.getDispatchId())
                && expected.operationId().equals(entity.getOperationId())
                && "JCS_SHA256_V1".equals(
                entity.getSafeBindingDigestVersion())
                && expected.bindingDigest().equals(entity.getSafeBindingDigest())
                && LifecycleOwnershipMode.ENFORCED.name().equals(
                entity.getOwnershipMode())
                && entity.getSourceSequence() == 1L
                && authorityFactId.equals(entity.getIdempotencyKey())
                && command.safeReasonCode().equals(entity.getSafeReasonCode());
        if (!exact) {
            throw new IllegalStateException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_FACT_CONFLICT");
        }
        return candidate;
    }

    private LifecycleFactEntity serverPreEffectFactEntity(
            ServerPreEffectAdmissionRejection command,
            TaskLifecycleFact fact) {
        LifecycleFactEntity entity = new LifecycleFactEntity();
        entity.setFactId(fact.factId());
        entity.setFactType(fact.type().name());
        entity.setSchemaVersion(1);
        entity.setAggregateType("TASK");
        entity.setAggregateId(command.taskId());
        entity.setTaskId(command.taskId());
        entity.setSessionId(fact.binding().sessionId());
        entity.setOperationId(fact.binding().operationId());
        entity.setPhysicalWorkerId(fact.binding().physicalWorkerId());
        entity.setStateGeneration(fact.binding().stateGeneration());
        entity.setInstanceEpoch(fact.binding().instanceEpoch());
        entity.setProviderTaskId(null);
        entity.setDispatchId(fact.binding().dispatchId());
        entity.setSafeBindingDigestVersion(command.bindingDigestVersion());
        entity.setSafeBindingDigest(fact.binding().bindingDigest());
        entity.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        entity.setSourceSequence(fact.sourceSequence());
        entity.setIdempotencyKey(fact.factId());
        entity.setSafeReasonCode(command.safeReasonCode());
        entity.setContentFreePayloadJson(json(fact));
        return entity;
    }

    private boolean exactServerPreEffectTerminalSnapshot(
            TaskLifecycleSnapshotEntity snapshot,
            ServerPreEffectAdmissionRejection command) {
        return TaskCanonicalPhase.TERMINAL.name().equals(
                snapshot.getCanonicalPhase())
                && TaskTerminalOutcome.FAILED.name().equals(
                snapshot.getTerminalOutcome())
                && TaskTerminalSource.SERVER_PRE_EFFECT_ADMISSION_REJECTION.name()
                .equals(snapshot.getTerminalSource())
                && LifecycleAvailability.AUTHORITY_QUARANTINED.name().equals(
                snapshot.getAvailability())
                && LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name()
                .equals(snapshot.getConflictState())
                && LifecycleOwnershipMode.ENFORCED.name().equals(
                snapshot.getOwnershipMode())
                && command.writerGenerationId().equals(
                snapshot.getWriterGenerationId());
    }

    private static String serverPreEffectFactId(String taskId) {
        try {
            String digest = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            taskId.getBytes(StandardCharsets.UTF_8)));
            return "server-pre-effect-admission-" + digest.substring(0, 48);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void observeAuthorizedEffectResult(
            NormalizedLifecycleFact normalized,
            TaskLifecycleFact fact) {
        if (fact.type()
                != TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED) {
            return;
        }
        String operation = normalized.operationId() == null
                ? normalized.dispatchId() : normalized.operationId();
        for (var effect : outbox.findByAggregateIdAndOperationId(
                normalized.taskId(), operation)) {
            if ("EFFECT_STARTED".equals(effect.getEffectState())) {
                effect.setEffectState("RESULT_OBSERVED");
                outbox.save(effect);
            }
        }
    }

    private TaskLifecycleFact normalizedFact(
            NormalizedLifecycleFact value, TaskLifecycleBinding expected) {
        TaskLifecycleFactType type;
        try {
            type = TaskLifecycleFactType.valueOf(value.factType());
        } catch (IllegalArgumentException unknown) {
            type = TaskLifecycleFactType.DIAGNOSTIC_TEXT;
        }
        boolean neverAccepted =
                type == TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED;
        if (!expected.physicalWorkerId().equals(value.workerIdentity().physicalWorkerId())
                || !expected.stateGeneration().equals(value.workerIdentity().stateGeneration())
                || !expected.instanceEpoch().equals(
                value.workerIdentity().instanceEpoch())
                || expected.ownershipMode() != value.ownershipMode()
                || (!neverAccepted && !Objects.equals(
                expected.providerTaskId(), value.providerTaskId()))) {
            throw new IllegalStateException("LIFECYCLE_NORMALIZED_FACT_BINDING_MISMATCH");
        }
        String operation = value.operationId() == null
                ? value.dispatchId() : value.operationId();
        boolean initialDispatch = expected.dispatchId().equals(
                value.dispatchId());
        if (initialDispatch) {
            if (!expected.bindingDigest().equals(value.safeBindingDigest())
                    || !operation.equals(expected.dispatchId())) {
                throw new IllegalStateException(
                        "LIFECYCLE_NORMALIZED_FACT_BINDING_MISMATCH");
            }
        } else {
            boolean authorizedTermination = outbox
                    .findByAggregateIdAndOperationId(
                            value.taskId(), operation).stream()
                    .anyMatch(effect ->
                            value.dispatchId().equals(effect.getDispatchId())
                            && value.safeBindingDigest().equals(
                            effect.getBindingDigest())
                            && value.ownershipMode().name().equals(
                            effect.getOwnershipMode())
                            && value.workerIdentity().stateGeneration().equals(
                            effect.getStateGeneration())
                            && value.workerIdentity().instanceEpoch().equals(
                            effect.getInstanceEpoch())
                            && value.safeBindingDigestVersion().equals(
                            effect.getBindingDigestVersion())
                            && expected.physicalWorkerId().equals(
                            effect.getPhysicalWorkerId())
                            && expected.providerTaskId().equals(
                            effect.getProviderTaskId())
                            && Set.of("EFFECT_STARTED", "RESULT_OBSERVED",
                            "COMPLETED").contains(effect.getEffectState()));
            if (!authorizedTermination) {
                throw new IllegalStateException(
                        "LIFECYCLE_TERMINATION_FACT_NOT_AUTHORIZED");
            }
        }
        // The durable entity below retains the normalized dispatch,
        // operation, digest and epoch verbatim.  The reducer receives the
        // immutable initial aggregate binding only after the ingress checks
        // above proved the alternate termination binding through its outbox.
        TaskLifecycleBinding factBinding = expected;
        if (type == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED) {
            TaskTerminalOutcome outcome;
            try {
                outcome = TaskTerminalOutcome.valueOf(
                        required(value.terminalOutcome(),
                                "LIFECYCLE_TERMINAL_OUTCOME_REQUIRED"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "LIFECYCLE_TERMINAL_OUTCOME_INVALID", invalid);
            }
            return TaskLifecycleFact.workerTerminal(
                    value.factId(), value.sourceSequence(), outcome, factBinding);
        }
        if (type == TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED) {
            if (!initialDispatch
                    || value.ownershipMode()
                    != LifecycleOwnershipMode.ENFORCED
                    || !"REJECTED".equals(value.acceptanceDisposition())
                    || !"PRE_EFFECT".equals(value.effectPhase())
                    || !Boolean.TRUE.equals(value.neverAcceptedProof())
                    || value.dispositionVersion() == null
                    || value.dispositionVersion() < 1L
                    || value.providerTaskId() != null) {
                throw new IllegalStateException(
                        "LIFECYCLE_NEVER_ACCEPTED_PROOF_INVALID");
            }
            if (!Set.of(
                    "WORKER_TASK_ADMISSION_CAPACITY_REJECTED",
                    "WORKER_TASK_ADMISSION_THREAD_CONFLICT",
                    "WORKER_TASK_RESUME_TARGET_NOT_FOUND")
                    .contains(value.safeReasonCode())) {
                throw new IllegalStateException(
                        "LIFECYCLE_NEVER_ACCEPTED_REASON_NOT_ALLOWLISTED");
            }
            return TaskLifecycleFact.exactPreEffectRejection(
                    value.factId(), value.sourceSequence(), factBinding);
        }
        return new TaskLifecycleFact(
                value.factId(), type, value.sourceSequence(), null,
                factBinding, false);
    }

    private LifecycleFactEntity factEntity(
            String taskId, TaskLifecycleFact fact, NormalizedLifecycleFact normalized) {
        LifecycleFactEntity entity = new LifecycleFactEntity();
        entity.setFactId(fact.factId());
        entity.setFactType(fact.type().name());
        entity.setSchemaVersion(normalized.schemaVersion());
        entity.setAggregateType("TASK");
        entity.setAggregateId(taskId);
        entity.setTaskId(taskId);
        entity.setSessionId(fact.binding().sessionId());
        entity.setOperationId(normalized.operationId() == null
                ? normalized.dispatchId() : normalized.operationId());
        entity.setPhysicalWorkerId(
                normalized.workerIdentity().physicalWorkerId());
        entity.setStateGeneration(
                normalized.workerIdentity().stateGeneration());
        entity.setInstanceEpoch(
                normalized.workerIdentity().instanceEpoch());
        entity.setProviderTaskId(normalized.providerTaskId());
        entity.setDispatchId(normalized.dispatchId());
        entity.setSafeBindingDigestVersion(normalized.safeBindingDigestVersion());
        entity.setSafeBindingDigest(normalized.safeBindingDigest());
        entity.setOwnershipMode(normalized.ownershipMode().name());
        entity.setSourceSequence(fact.sourceSequence());
        entity.setIdempotencyKey(normalized.idempotencyKey());
        entity.setSafeReasonCode(normalized.safeReasonCode());
        entity.setContentFreePayloadJson(json(fact));
        return entity;
    }

    private TaskLifecycleFact fact(LifecycleFactEntity entity) {
        try {
            var payload = objectMapper.readTree(
                    entity.getContentFreePayloadJson());
            if (payload.isObject() && payload.isEmpty()) {
                // Admission facts written before the full fact envelope was
                // persisted intentionally used an empty content-free payload.
                // Rebuild their reducer fields from the immutable columns so
                // existing in-flight tasks can still converge at terminal.
                TaskLifecycleFactType type = TaskLifecycleFactType.valueOf(
                        entity.getFactType());
                if (!Set.of(
                        TaskLifecycleFactType.TASK_DISPATCH_RESERVED,
                        TaskLifecycleFactType.TASK_DISPATCHED).contains(type)) {
                    throw new IllegalArgumentException(
                            "LIFECYCLE_EMPTY_FACT_PAYLOAD_NOT_SUPPORTED");
                }
                return TaskLifecycleFact.of(
                        entity.getFactId(),
                        type,
                        entity.getSourceSequence());
            }
            return objectMapper.readValue(
                    entity.getContentFreePayloadJson(), TaskLifecycleFact.class);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalStateException("LIFECYCLE_FACT_PAYLOAD_INVALID", invalid);
        }
    }

    private void applyDecision(
            TaskLifecycleSnapshotEntity entity, TaskLifecycleDecision decision) {
        TaskLifecycleSnapshot snapshot = decision.snapshot();
        entity.setCanonicalPhase(snapshot.canonicalPhase().name());
        entity.setTerminalOutcome(snapshot.terminalOutcome() == null
                ? null : snapshot.terminalOutcome().name());
        entity.setTerminalSource(snapshot.terminalSource() == null
                ? null : snapshot.terminalSource().name());
        if (entity.getConflictState() == null
                || LifecycleConflictState.NONE.name().equals(
                entity.getConflictState())) {
            entity.setAvailability(snapshot.availability().name());
            entity.setConflictState(snapshot.conflictState().name());
        } else {
            // Reducer output may advance canonical/local-safety state, but an
            // ordinary fact batch cannot clear an independently committed
            // authority quarantine. Recovery requires an explicit protocol.
            entity.setAvailability(
                    LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        }
        entity.setCleanupState(snapshot.cleanupState().name());
        entity.setFactCursor(snapshot.factCursor());
        entity.setPolicyVersion(snapshot.policyVersion());
        entity.setSnapshotJson(json(snapshot));
    }

    private TaskLifecycleBinding binding(TaskLifecycleSnapshotEntity entity) {
        return new TaskLifecycleBinding(
                entity.getSessionId(),
                entity.getPhysicalWorkerId(),
                entity.getStateGeneration(),
                entity.getInstanceEpoch(),
                LifecycleOwnershipMode.valueOf(entity.getOwnershipMode()),
                entity.getDispatchId(),
                entity.getOperationId(),
                entity.getSafeBindingDigest(),
                entity.getProviderTaskId());
    }

    private void applyBinding(
            TaskLifecycleSnapshotEntity entity,
            TaskLifecycleBinding binding,
            String digestVersion) {
        entity.setSessionId(binding.sessionId());
        entity.setPhysicalWorkerId(binding.physicalWorkerId());
        entity.setStateGeneration(binding.stateGeneration());
        entity.setInstanceEpoch(binding.instanceEpoch());
        entity.setOwnershipMode(binding.ownershipMode().name());
        entity.setProviderTaskId(binding.providerTaskId());
        entity.setDispatchId(binding.dispatchId());
        entity.setOperationId(binding.operationId());
        entity.setSafeBindingDigestVersion(digestVersion);
        entity.setSafeBindingDigest(binding.bindingDigest());
    }

    private void requireExact(
            TaskLifecycleSnapshotEntity entity, TaskLifecycleBinding candidate) {
        if (!binding(entity).exactRuntimeMatch(candidate)) {
            throw new IllegalStateException("LIFECYCLE_TASK_BINDING_MISMATCH");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_CONTENT_FREE_SERIALIZATION_FAILED", error);
        }
    }

    private static String required(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
        return value;
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

    public record ServerPreEffectAdmissionRejection(
            String taskId,
            String sessionId,
            String physicalWorkerId,
            WorkerLifecycleIdentity workerIdentity,
            String dispatchId,
            String bindingDigestVersion,
            String bindingDigest,
            String writerGenerationId,
            String safeReasonCode) {
    }

}

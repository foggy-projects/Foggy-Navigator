package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final TaskLifecycleSnapshotRepository snapshots;
    private final SessionTaskRepository canonicalTasks;
    private final TaskTerminalCommitService terminalCommit;
    private final TerminalCleanupHandler cleanup;
    private final ObjectMapper objectMapper;
    private final TaskLifecycleReducer reducer = new TaskLifecycleReducer();

    public TaskLifecycleOwnerService(
            LifecycleFactRepository facts,
            TaskLifecycleSnapshotRepository snapshots,
            SessionTaskRepository canonicalTasks,
            TaskTerminalCommitService terminalCommit,
            TerminalCleanupHandler cleanup,
            ObjectMapper objectMapper) {
        this.facts = facts;
        this.snapshots = snapshots;
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
        SessionTaskEntity canonical = canonicalTasks.findByTaskId(workerTask.navigatorTaskId())
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
                            == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED)
                    .filter(TaskLifecycleFact::exactTerminalAuthority)
                    .findFirst().orElseThrow();
            terminalCommit.commit(new TerminalCommitCommand(
                    new TerminalTombstoneContext(
                            taskId,
                            canonical.getSessionId(),
                            canonical.getProviderType(),
                            canonical.getTenantId(),
                            expected.providerTaskId(),
                            canonical.getUserId(),
                            canonical.getAgentId(),
                            expected.operationId().equals(expected.dispatchId())
                                    ? null : expected.operationId()),
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

    private TaskLifecycleFact normalizedFact(
            NormalizedLifecycleFact value, TaskLifecycleBinding expected) {
        if (!expected.physicalWorkerId().equals(value.workerIdentity().physicalWorkerId())
                || !expected.stateGeneration().equals(value.workerIdentity().stateGeneration())
                || !expected.instanceEpoch().equals(value.workerIdentity().instanceEpoch())
                || expected.ownershipMode() != value.ownershipMode()
                || !expected.dispatchId().equals(value.dispatchId())
                || !expected.bindingDigest().equals(value.safeBindingDigest())
                || !expected.providerTaskId().equals(value.providerTaskId())) {
            throw new IllegalStateException("LIFECYCLE_NORMALIZED_FACT_BINDING_MISMATCH");
        }
        String operation = value.operationId() == null
                ? value.dispatchId() : value.operationId();
        TaskLifecycleBinding factBinding = new TaskLifecycleBinding(
                expected.sessionId(),
                value.workerIdentity().physicalWorkerId(),
                value.workerIdentity().stateGeneration(),
                value.workerIdentity().instanceEpoch(),
                value.ownershipMode(),
                value.dispatchId(),
                operation,
                value.safeBindingDigest(),
                value.providerTaskId());
        TaskLifecycleFactType type;
        try {
            type = TaskLifecycleFactType.valueOf(value.factType());
        } catch (IllegalArgumentException unknown) {
            type = TaskLifecycleFactType.DIAGNOSTIC_TEXT;
        }
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
        entity.setOperationId(fact.binding().operationId());
        entity.setPhysicalWorkerId(fact.binding().physicalWorkerId());
        entity.setStateGeneration(fact.binding().stateGeneration());
        entity.setInstanceEpoch(fact.binding().instanceEpoch());
        entity.setProviderTaskId(fact.binding().providerTaskId());
        entity.setDispatchId(fact.binding().dispatchId());
        entity.setSafeBindingDigestVersion(normalized.safeBindingDigestVersion());
        entity.setSafeBindingDigest(fact.binding().bindingDigest());
        entity.setOwnershipMode(fact.binding().ownershipMode().name());
        entity.setSourceSequence(fact.sourceSequence());
        entity.setIdempotencyKey(normalized.idempotencyKey());
        entity.setSafeReasonCode(normalized.safeReasonCode());
        entity.setContentFreePayloadJson(json(fact));
        return entity;
    }

    private TaskLifecycleFact fact(LifecycleFactEntity entity) {
        try {
            return objectMapper.readValue(
                    entity.getContentFreePayloadJson(), TaskLifecycleFact.class);
        } catch (JsonProcessingException invalid) {
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
        entity.setAvailability(snapshot.availability().name());
        entity.setConflictState(snapshot.conflictState().name());
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
}

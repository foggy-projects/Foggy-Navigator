package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleCommandAuthorizationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class TaskTerminationIntentRecorder implements RuntimeTerminationIntentPort,
        WorkerLifecycleCommandAuthorizationPort {
    private final LifecycleEffectOutboxRepository outbox;
    private final TaskLifecycleSnapshotRepository snapshots;
    private final WorkerLifecycleSnapshotRepository workers;
    private final SessionLifecycleSnapshotRepository sessions;
    private final LifecycleWriterProofRepository proofs;
    private final LifecycleWriterProofReferenceRepository references;
    private final SessionTaskRepository canonicalTasks;
    private final WriterExclusivityProofService writerProofs;
    private final LifecycleAuthorityClock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskTerminationIntentRecorder(
            LifecycleEffectOutboxRepository outbox,
            TaskLifecycleSnapshotRepository snapshots,
            WorkerLifecycleSnapshotRepository workers,
            SessionLifecycleSnapshotRepository sessions,
            LifecycleWriterProofRepository proofs,
            LifecycleWriterProofReferenceRepository references,
            SessionTaskRepository canonicalTasks,
            WriterExclusivityProofService writerProofs,
            LifecycleAuthorityClock clock) {
        this.outbox = outbox;
        this.snapshots = snapshots;
        this.workers = workers;
        this.sessions = sessions;
        this.proofs = proofs;
        this.references = references;
        this.canonicalTasks = canonicalTasks;
        this.writerProofs = writerProofs;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RuntimeTerminationDelivery recordIntent(RuntimeTerminationIntent intent) {
        requireIntent(intent);
        AdmissionFence fence = requireOwnerAdmission(intent, clock.databaseNow());
        String key = key(intent.clientRequestId());
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        if (outbox.existsById(id)) {
            LifecycleEffectOutboxEntity existing = outbox.findById(id).orElseThrow();
            requireSameIntent(existing, intent);
            return delivery(existing);
        }
        LifecycleEffectOutboxEntity entity = new LifecycleEffectOutboxEntity();
        entity.setEffectId(id);
        entity.setAggregateType("TASK");
        entity.setAggregateId(intent.taskId());
        entity.setEffectType("TERMINATION_REQUEST");
        entity.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        entity.setEffectState("PREPARED");
        entity.setIdempotencyKey(key);
        entity.setProviderType(intent.providerType());
        entity.setPhysicalWorkerId(intent.physicalWorkerId());
        entity.setProviderTaskId(intent.providerTaskId());
        entity.setDispatchId(intent.dispatchId());
        entity.setOperationId(intent.operationId());
        entity.setOwnershipMode(intent.ownershipMode());
        entity.setStateGeneration(intent.stateGeneration());
        entity.setInstanceEpoch(intent.instanceEpoch());
        entity.setBindingDigestVersion(intent.bindingDigestVersion());
        entity.setBindingDigest(intent.bindingDigest());
        entity.setEffectClaim("TERMINATION_PROVIDER_CALL");
        entity.setProofId(fence.proofId());
        entity.setAggregateReferenceId(fence.taskReferenceId());
        entity.setWriterGenerationId(fence.writerGenerationId());
        entity.setControllerInventoryDigest(
                fence.controllerInventoryDigest());
        entity.setContentFreePayloadJson("{}");
        return delivery(outbox.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeTerminationDelivery find(String clientRequestId) {
        return outbox.findByIdempotencyKey(key(clientRequestId))
                .map(this::delivery).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeTerminationDelivery> findPrepared(int limit) {
        if (limit < 1) return List.of();
        return outbox
                .findTop100ByEffectTypeAndEffectStateOrderByCreatedAtAsc(
                        "TERMINATION_REQUEST", "PREPARED")
                .stream().limit(Math.min(limit, 100))
                .map(this::delivery).toList();
    }

    @Override
    @Transactional
    public RuntimeTerminationAuthorization authorizeEffect(String clientRequestId) {
        LifecycleEffectOutboxEntity entity = outbox.findByIdempotencyKey(
                        key(clientRequestId))
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_DELIVERY_NOT_FOUND"));
        RuntimeTerminationDelivery delivery = delivery(entity);
        if ("RESULT_OBSERVED".equals(entity.getEffectState())
                || "COMPLETED".equals(entity.getEffectState())) {
            return new RuntimeTerminationAuthorization(
                    delivery, false, true, true, "RESULT_ALREADY_OBSERVED");
        }
        if ("EFFECT_STARTED".equals(entity.getEffectState())) {
            return new RuntimeTerminationAuthorization(
                    delivery, false, true, false, "EFFECT_ALREADY_STARTED");
        }
        if (!Set.of("PREPARED", "CLAIMED").contains(
                entity.getEffectState())) {
            throw new IllegalStateException("TERMINATION_DELIVERY_STATE_INVALID");
        }
        var snapshot = snapshots.findById(entity.getAggregateId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_TASK_NOT_ENROLLED"));
        var proof = proofs.findById(entity.getProofId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_PROOF_NOT_FOUND"));
        String workerReference = referenceId(
                proof.getProofId(), ProofAggregateType.WORKER,
                snapshot.getPhysicalWorkerId());
        String sessionReference = referenceId(
                proof.getProofId(), ProofAggregateType.SESSION,
                snapshot.getSessionId());
        var authorization = writerProofs.authorizeEffect(
                new WriterExclusivityProofService.EffectAuthorizationCommand(
                        entity.getEffectId(), proof.getProofId(),
                        entity.getAggregateReferenceId(),
                        ProofAggregateType.TASK, entity.getAggregateId(),
                        entity.getWriterGenerationId(),
                        entity.getControllerInventoryDigest(),
                        entity.getEffectClaim(),
                        workerReference, snapshot.getPhysicalWorkerId(),
                        sessionReference, snapshot.getSessionId()),
                clock.databaseNow());
        LifecycleEffectOutboxEntity authorized = outbox.findById(
                entity.getEffectId()).orElseThrow();
        return new RuntimeTerminationAuthorization(
                delivery(authorized),
                authorization.providerCallAuthorized(),
                authorization.alreadyStarted(), false,
                authorization.safeReasonCode());
    }

    @Override
    @Transactional
    public void resultObserved(String clientRequestId, String safeResultCode) {
        LifecycleEffectOutboxEntity entity = outbox.findByIdempotencyKey(
                        key(clientRequestId))
                .flatMap(value -> outbox.findForUpdate(value.getEffectId()))
                .orElseThrow();
        if ("RESULT_OBSERVED".equals(entity.getEffectState())) return;
        if (!"EFFECT_STARTED".equals(entity.getEffectState())) {
            throw new IllegalStateException("TERMINATION_EFFECT_NOT_STARTED");
        }
        entity.setEffectState("RESULT_OBSERVED");
        entity.setContentFreePayloadJson(
                "{\"safeResultCode\":\"" + safe(safeResultCode) + "\"}");
        outbox.save(entity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedCommand prepare(WorkerLifecycleCommand command) {
        requireCommand(command);
        var lifecycle = snapshots.findById(command.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_TASK_NOT_ENROLLED"));
        RuntimeTerminationIntent intent = new RuntimeTerminationIntent(
                command.operationId(), command.taskId(), requireSessionId(command.taskId()),
                command.providerType(), command.physicalWorkerId(),
                command.providerTaskId(), command.dispatchId(),
                command.operationId(), lifecycle.getOwnershipMode(),
                lifecycle.getStateGeneration(), lifecycle.getInstanceEpoch(),
                "JCS_SHA256_V1", command.bindingDigest());
        AdmissionFence fence = requireOwnerAdmission(intent, clock.databaseNow());
        LifecycleEffectOutboxEntity parent = outbox
                .findByAggregateIdAndOperationId(
                        command.taskId(), command.operationId()).stream()
                .filter(value -> "TERMINATION_REQUEST".equals(value.getEffectType()))
                .filter(value -> "EFFECT_STARTED".equals(value.getEffectState()))
                .filter(value -> command.providerType().equals(value.getProviderType()))
                .filter(value -> command.physicalWorkerId().equals(
                        value.getPhysicalWorkerId()))
                .filter(value -> command.providerTaskId().equals(
                        value.getProviderTaskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_PARENT_AUTHORIZATION_REQUIRED"));
        if (!fence.proofId().equals(parent.getProofId())
                || !fence.taskReferenceId().equals(
                parent.getAggregateReferenceId())) {
            throw new IllegalStateException(
                    "TERMINATION_PARENT_PROOF_MISMATCH");
        }

        String idempotency = "worker-command:" + command.taskId() + ":"
                + command.operationId() + ":" + command.dispatchId();
        String effectId = UUID.nameUUIDFromBytes(
                idempotency.getBytes(StandardCharsets.UTF_8)).toString();
        LifecycleEffectOutboxEntity existing = outbox.findById(effectId)
                .orElse(null);
        if (existing != null) {
            requireSameCommand(existing, command);
            return prepared(existing);
        }
        LifecycleEffectOutboxEntity entity = new LifecycleEffectOutboxEntity();
        entity.setEffectId(effectId);
        entity.setAggregateType("TASK");
        entity.setAggregateId(command.taskId());
        entity.setEffectType("WORKER_LIFECYCLE_COMMAND");
        entity.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        entity.setEffectState("PREPARED");
        entity.setIdempotencyKey(idempotency);
        entity.setProviderType(command.providerType());
        entity.setPhysicalWorkerId(command.physicalWorkerId());
        entity.setProviderTaskId(command.providerTaskId());
        entity.setDispatchId(command.dispatchId());
        entity.setOperationId(command.operationId());
        entity.setOwnershipMode(intent.ownershipMode());
        entity.setStateGeneration(intent.stateGeneration());
        entity.setInstanceEpoch(intent.instanceEpoch());
        entity.setBindingDigestVersion(intent.bindingDigestVersion());
        entity.setBindingDigest(command.bindingDigest());
        entity.setEffectClaim("CODEX_WORKER_TERMINATION_CALL");
        entity.setProofId(fence.proofId());
        entity.setAggregateReferenceId(fence.taskReferenceId());
        entity.setWriterGenerationId(fence.writerGenerationId());
        entity.setControllerInventoryDigest(
                fence.controllerInventoryDigest());
        entity.setContentFreePayloadJson("{}");
        return prepared(outbox.save(entity));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Authorization authorize(String effectId) {
        LifecycleEffectOutboxEntity entity = outbox.findForUpdate(effectId)
                .orElseThrow(() -> new IllegalStateException(
                        "WORKER_COMMAND_OUTBOX_NOT_FOUND"));
        if (!"WORKER_LIFECYCLE_COMMAND".equals(entity.getEffectType())) {
            throw new IllegalStateException(
                    "WORKER_COMMAND_OUTBOX_TYPE_INVALID");
        }
        if ("EFFECT_STARTED".equals(entity.getEffectState())
                || "RESULT_OBSERVED".equals(entity.getEffectState())
                || "COMPLETED".equals(entity.getEffectState())) {
            return new Authorization(prepared(entity), false, true,
                    "EFFECT_ALREADY_STARTED");
        }
        var snapshot = snapshots.findById(entity.getAggregateId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_TASK_NOT_ENROLLED"));
        var proof = proofs.findById(entity.getProofId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_PROOF_NOT_FOUND"));
        var result = writerProofs.authorizeEffect(
                new WriterExclusivityProofService.EffectAuthorizationCommand(
                        entity.getEffectId(), proof.getProofId(),
                        entity.getAggregateReferenceId(),
                        ProofAggregateType.TASK, entity.getAggregateId(),
                        entity.getWriterGenerationId(),
                        entity.getControllerInventoryDigest(),
                        entity.getEffectClaim(),
                        referenceId(proof.getProofId(),
                                ProofAggregateType.WORKER,
                                snapshot.getPhysicalWorkerId()),
                        snapshot.getPhysicalWorkerId(),
                        referenceId(proof.getProofId(),
                                ProofAggregateType.SESSION,
                                snapshot.getSessionId()),
                        snapshot.getSessionId()),
                clock.databaseNow());
        LifecycleEffectOutboxEntity authorized = outbox.findById(effectId)
                .orElseThrow();
        return new Authorization(prepared(authorized),
                result.providerCallAuthorized(), result.alreadyStarted(),
                result.safeReasonCode());
    }

    private String key(String clientRequestId) {
        return "termination-intent:" + clientRequestId;
    }

    private String requireSessionId(String taskId) {
        return snapshots.findById(taskId)
                .map(value -> value.getSessionId())
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_SESSION_BINDING_REQUIRED"));
    }

    private PreparedCommand prepared(LifecycleEffectOutboxEntity entity) {
        return new PreparedCommand(entity.getEffectId(),
                entity.getEffectState(), entity.getBindingDigest());
    }

    private void requireCommand(WorkerLifecycleCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "WORKER_LIFECYCLE_COMMAND_REQUIRED");
        }
        required(command.taskId(), "TASK_ID");
        required(command.providerType(), "PROVIDER_TYPE");
        required(command.physicalWorkerId(), "PHYSICAL_WORKER_ID");
        required(command.providerTaskId(), "PROVIDER_TASK_ID");
        required(command.dispatchId(), "DISPATCH_ID");
        required(command.operationId(), "OPERATION_ID");
        required(command.bindingDigest(), "BINDING_DIGEST");
    }

    private void requireSameCommand(
            LifecycleEffectOutboxEntity existing,
            WorkerLifecycleCommand command) {
        if (!command.taskId().equals(existing.getAggregateId())
                || !command.providerType().equals(existing.getProviderType())
                || !command.physicalWorkerId().equals(
                existing.getPhysicalWorkerId())
                || !command.providerTaskId().equals(
                existing.getProviderTaskId())
                || !command.dispatchId().equals(existing.getDispatchId())
                || !command.operationId().equals(existing.getOperationId())
                || !command.bindingDigest().equals(
                existing.getBindingDigest())) {
            throw new IllegalStateException(
                    "WORKER_COMMAND_BINDING_MISMATCH");
        }
    }

    private RuntimeTerminationDelivery delivery(LifecycleEffectOutboxEntity entity) {
        return new RuntimeTerminationDelivery(
                entity.getEffectId(),
                entity.getIdempotencyKey().substring("termination-intent:".length()),
                entity.getAggregateId(),
                entity.getProviderType(),
                entity.getPhysicalWorkerId(),
                entity.getProviderTaskId(),
                entity.getOperationId(),
                entity.getOwnershipMode(),
                entity.getStateGeneration(),
                entity.getInstanceEpoch(),
                entity.getBindingDigestVersion(),
                entity.getBindingDigest(),
                entity.getEffectState());
    }

    private String safe(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,96}")
                ? value : "RESULT_OBSERVED";
    }

    private void requireIntent(RuntimeTerminationIntent intent) {
        if (intent == null) throw new IllegalArgumentException(
                "TERMINATION_INTENT_REQUIRED");
        required(intent.clientRequestId(), "CLIENT_REQUEST_ID");
        required(intent.taskId(), "TASK_ID");
        required(intent.sessionId(), "SESSION_ID");
        required(intent.providerType(), "PROVIDER_TYPE");
        required(intent.physicalWorkerId(), "PHYSICAL_WORKER_ID");
        required(intent.providerTaskId(), "PROVIDER_TASK_ID");
        required(intent.dispatchId(), "DISPATCH_ID");
        required(intent.operationId(), "OPERATION_ID");
        required(intent.ownershipMode(), "OWNERSHIP_MODE");
        required(intent.stateGeneration(), "STATE_GENERATION");
        required(intent.instanceEpoch(), "INSTANCE_EPOCH");
        required(intent.bindingDigestVersion(), "BINDING_DIGEST_VERSION");
        required(intent.bindingDigest(), "BINDING_DIGEST");
        if (!"ENFORCED".equals(intent.ownershipMode())) {
            throw new IllegalArgumentException(
                    "TERMINATION_OWNERSHIP_MODE_NOT_ENFORCED");
        }
        if (!"JCS_SHA256_V1".equals(intent.bindingDigestVersion())) {
            throw new IllegalArgumentException(
                    "TERMINATION_BINDING_DIGEST_VERSION_UNSUPPORTED");
        }
    }

    private void requireSameIntent(
            LifecycleEffectOutboxEntity existing,
            RuntimeTerminationIntent intent) {
        if (!intent.taskId().equals(existing.getAggregateId())
                || !intent.providerType().equals(existing.getProviderType())
                || !intent.physicalWorkerId().equals(existing.getPhysicalWorkerId())
                || !intent.providerTaskId().equals(existing.getProviderTaskId())
                || !intent.dispatchId().equals(existing.getDispatchId())
                || !intent.operationId().equals(existing.getOperationId())
                || !intent.ownershipMode().equals(existing.getOwnershipMode())
                || !intent.stateGeneration().equals(existing.getStateGeneration())
                || !intent.instanceEpoch().equals(existing.getInstanceEpoch())
                || !intent.bindingDigestVersion().equals(
                existing.getBindingDigestVersion())
                || !intent.bindingDigest().equals(existing.getBindingDigest())) {
            throw new IllegalStateException(
                    "TERMINATION_DELIVERY_BINDING_MISMATCH");
        }
    }

    private AdmissionFence requireOwnerAdmission(
            RuntimeTerminationIntent intent, LocalDateTime authorityNow) {
        var snapshot = snapshots.findForUpdate(intent.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_OWNER_ENROLLMENT_REQUIRED"));
        var canonical = canonicalTasks.findByTaskIdForUpdate(intent.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_TASK_REQUIRED"));
        var worker = workers.findForUpdate(intent.physicalWorkerId())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_WORKER_ENROLLMENT_REQUIRED"));
        var session = sessions.findForUpdate(intent.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_SESSION_ENROLLMENT_REQUIRED"));
        if (!intent.ownershipMode().equals(snapshot.getOwnershipMode())
                || !intent.ownershipMode().equals(worker.getOwnershipMode())
                || !intent.ownershipMode().equals(session.getOwnershipMode())
                || (!intent.sessionId().equals(snapshot.getSessionId())
                || !intent.physicalWorkerId()
                .equals(snapshot.getPhysicalWorkerId())
                || !intent.physicalWorkerId()
                .equals(session.getPhysicalWorkerId())
                || !intent.providerTaskId()
                .equals(snapshot.getProviderTaskId())
                || !intent.providerType().equals(canonical.getProviderType())
                || !intent.sessionId().equals(canonical.getSessionId())
                || !intent.providerTaskId().equals(
                canonical.getProviderTaskId())
                || !intent.physicalWorkerId().equals(canonical.getWorkerId())
                || !intent.stateGeneration().equals(
                snapshot.getStateGeneration())
                || !intent.stateGeneration().equals(
                worker.getStateGeneration())
                || !intent.instanceEpoch().equals(snapshot.getInstanceEpoch())
                || !intent.instanceEpoch().equals(worker.getInstanceEpoch())
                || !LifecycleAvailability.READY.name().equals(
                snapshot.getAvailability())
                || !LifecycleAvailability.READY.name().equals(
                worker.getAvailability())
                || !LifecycleAvailability.READY.name().equals(
                session.getAvailability())
                || !LifecycleConflictState.NONE.name().equals(
                snapshot.getConflictState())
                || !LifecycleConflictState.NONE.name().equals(
                worker.getConflictState())
                || !LifecycleConflictState.NONE.name().equals(
                session.getConflictState())
                || !Objects.equals(snapshot.getWriterGenerationId(),
                worker.getWriterGenerationId())
                || !Objects.equals(snapshot.getWriterGenerationId(),
                session.getWriterGenerationId()))) {
            throw new IllegalStateException(
                    "TERMINATION_OWNER_BINDING_MISMATCH");
        }
        var activeProofs = proofs.findByGenerationIdAndStatus(
                snapshot.getWriterGenerationId(), "ACTIVE").stream()
                .filter(proof -> proof.getExpiresAt().isAfter(
                        authorityNow)).toList();
        if (activeProofs.size() != 1) {
            throw new IllegalStateException(
                    "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }
        var proof = activeProofs.get(0);
        String workerReference = referenceId(
                proof.getProofId(), ProofAggregateType.WORKER,
                intent.physicalWorkerId());
        String sessionReference = referenceId(
                proof.getProofId(), ProofAggregateType.SESSION,
                intent.sessionId());
        String taskReference = referenceId(
                proof.getProofId(), ProofAggregateType.TASK,
                intent.taskId());
        for (ExpectedReference expected : List.of(
                new ExpectedReference(workerReference,
                        ProofAggregateType.WORKER,
                        intent.physicalWorkerId()),
                new ExpectedReference(sessionReference,
                        ProofAggregateType.SESSION, intent.sessionId()),
                new ExpectedReference(taskReference,
                        ProofAggregateType.TASK, intent.taskId()))) {
            var value = references.findById(expected.referenceId())
                    .orElseThrow(() ->
                    new IllegalStateException(
                            "LIFECYCLE_AGGREGATE_REFERENCE_NOT_FOUND"));
            if (!proof.getProofId().equals(value.getProofId())
                    || !expected.type().name().equals(
                    value.getAggregateType())
                    || !expected.aggregateId().equals(value.getAggregateId())
                    || value.getReleasedAt() != null) {
                throw new IllegalStateException(
                        "LIFECYCLE_AGGREGATE_REFERENCE_INVALID");
            }
        }
        return new AdmissionFence(
                proof.getProofId(), snapshot.getWriterGenerationId(),
                proof.getControllerInventoryDigest(), taskReference);
    }

    private String referenceId(
            String proofId, ProofAggregateType type, String aggregateId) {
        return proofId + ":" + type.name() + ":" + aggregateId;
    }

    private record AdmissionFence(
            String proofId,
            String writerGenerationId,
            String controllerInventoryDigest,
            String taskReferenceId) {
    }

    private record ExpectedReference(
            String referenceId,
            ProofAggregateType type,
            String aggregateId) {
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "TERMINATION_" + field + "_REQUIRED");
        }
    }
}

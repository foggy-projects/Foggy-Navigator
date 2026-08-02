package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.CodexModelCanonicalizer;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class LifecycleProductionAdmissionService {
    private static final String POLICY = "ARCH-001-ACT-001";

    private final LifecycleActivationAuthorityService authority;
    private final LifecycleAuthorityClock clock;
    private final WorkerLifecycleSnapshotRepository workers;
    private final SessionLifecycleSnapshotRepository sessions;
    private final TaskLifecycleSnapshotRepository tasks;
    private final LifecycleFactRepository facts;
    private final LifecycleEffectOutboxRepository outbox;
    private final LifecycleWriterProofReferenceRepository references;
    private final SessionTaskRepository canonicalTasks;
    private final WriterExclusivityProofService writerProofs;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public LifecycleProductionAdmissionService(
            LifecycleActivationAuthorityService authority,
            LifecycleAuthorityClock clock,
            WorkerLifecycleSnapshotRepository workers,
            SessionLifecycleSnapshotRepository sessions,
            TaskLifecycleSnapshotRepository tasks,
            LifecycleFactRepository facts,
            LifecycleEffectOutboxRepository outbox,
            LifecycleWriterProofReferenceRepository references,
            SessionTaskRepository canonicalTasks,
            WriterExclusivityProofService writerProofs,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.authority = authority;
        this.clock = clock;
        this.workers = workers;
        this.sessions = sessions;
        this.tasks = tasks;
        this.facts = facts;
        this.outbox = outbox;
        this.references = references;
        this.canonicalTasks = canonicalTasks;
        this.writerProofs = writerProofs;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ActivationReservation reserveProductionAdmission(
            ProductionAdmissionRequest request) {
        if (!authority.properties().isAdmissionEnabled()) {
            return ActivationReservation.notRequired();
        }
        authority.requireAdmissionEnabled();
        LocalDateTime now = clock.databaseNow();
        var loaded = authority.loadVerifiedArtifacts(now);
        var target = authority.targetRepository()
                .findForUpdate(authority.properties().getExactTargetId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.TARGET_NOT_REGISTERED));
        authority.requireTargetAuthorityLocked(
                target, loaded, now, Set.of("READY"));
        requireExactRequest(request, loaded.manifest(), target.getStatus());
        if (target.getReservedTaskId() != null
                || target.getReservedSessionId() != null) {
            throw denied(LifecycleActivationReason.TARGET_CONSUMED);
        }
        target.setReservedTaskId(request.taskId());
        target.setReservedSessionId(request.sessionId());
        target.setReservedAt(now);
        target.setStatus("RESERVED");
        target.setSafeReasonCode(null);
        authority.targetRepository().save(target);
        return new ActivationReservation(
                true, target.getTargetId(), target.getRunId(),
                target.getProofId(), target.getGenerationId(),
                target.getControllerInventoryDigest(),
                request.sessionId(), request.taskId(),
                "LIFECYCLE_ACTIVATION_TARGET_RESERVED");
    }

    public LifecycleOwnershipMode ownershipModeForTask(String taskId) {
        if (!authority.properties().isAdmissionEnabled()) {
            return LifecycleOwnershipMode.SHADOW;
        }
        return transactions.execute(status -> {
            var target = authority.targetRepository().findById(
                            required(authority.properties().getExactTargetId(),
                                    LifecycleActivationReason
                                            .TARGET_NOT_CONFIGURED))
                    .orElseThrow(() -> denied(
                            LifecycleActivationReason.TARGET_NOT_REGISTERED));
            if (!"RESERVED".equals(target.getStatus())
                    || !Objects.equals(target.getReservedTaskId(), taskId)) {
                throw denied(LifecycleActivationReason
                        .ADMISSION_BINDING_MISMATCH);
            }
            return LifecycleOwnershipMode.ENFORCED;
        });
    }

    public ProviderEffectAuthorization admitAndAuthorizeProviderEffect(
            ProviderEffectCommand command) {
        if (!authority.properties().isAdmissionEnabled()) {
            return ProviderEffectAuthorization.notRequired();
        }
        return transactions.execute(status -> admitLocked(command));
    }

    public void observeAcceptedDisposition(AcceptedDisposition disposition) {
        if (!authority.properties().isAdmissionEnabled()) return;
        try {
            transactions.executeWithoutResult(status ->
                    observeAcceptedLocked(disposition));
        } catch (RuntimeException mismatch) {
            authority.quarantineConfiguredTarget(
                    LifecycleActivationReason.ADMISSION_BINDING_MISMATCH);
            throw mismatch;
        }
    }

    private ProviderEffectAuthorization admitLocked(
            ProviderEffectCommand command) {
        LocalDateTime now = clock.databaseNow();
        var loaded = authority.loadVerifiedArtifacts(now);
        var target = authority.targetRepository()
                .findForUpdate(authority.properties().getExactTargetId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.TARGET_NOT_REGISTERED));
        authority.requireTargetAuthorityLocked(
                target, loaded, now, Set.of("RESERVED"));
        requireReservedBinding(target, command);

        SessionTaskEntity canonical = canonicalTasks
                .findByTaskIdForUpdate(command.taskId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.ADMISSION_BINDING_MISMATCH));
        requireCanonicalBinding(target, canonical);

        var worker = workers.findForUpdate(command.physicalWorkerId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.WORKER_NOT_READY));
        if (!"SHADOW".equals(worker.getOwnershipMode())
                || !LifecycleAvailability.READY.name().equals(
                worker.getAvailability())
                || !LifecycleConflictState.NONE.name().equals(
                worker.getConflictState())
                || !command.workerIdentity().stateGeneration().equals(
                worker.getStateGeneration())
                || !command.workerIdentity().instanceEpoch().equals(
                worker.getInstanceEpoch())
                || !Objects.equals(target.getWorkerStateGeneration(),
                worker.getStateGeneration())
                || !Objects.equals(target.getWorkerInstanceEpoch(),
                worker.getInstanceEpoch())) {
            throw denied(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
        }
        worker.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        worker.setWriterGenerationId(target.getGenerationId());
        workers.save(worker);

        SessionLifecycleSnapshotEntity session = sessions
                .findForUpdate(command.sessionId()).orElse(null);
        if (session != null && (!"SHADOW".equals(session.getOwnershipMode())
                || (session.getForegroundTaskId() != null
                && !command.taskId().equals(session.getForegroundTaskId())))) {
            throw denied("SESSION_FOREGROUND_LANE_OCCUPIED");
        }
        if (session == null) {
            session = new SessionLifecycleSnapshotEntity();
            session.setSessionId(command.sessionId());
            session.setCanonicalPhase("OPEN");
        }
        session.setPhysicalWorkerId(command.physicalWorkerId());
        session.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        session.setForegroundTaskId(command.taskId());
        session.setForegroundLaneState("OCCUPIED");
        session.setAvailability(LifecycleAvailability.READY.name());
        session.setConflictState(LifecycleConflictState.NONE.name());
        session.setWriterGenerationId(target.getGenerationId());
        sessions.save(session);

        if (tasks.existsById(command.taskId())) {
            throw denied(LifecycleActivationReason.ADMISSION_BINDING_MISMATCH);
        }
        TaskLifecycleSnapshotEntity task = new TaskLifecycleSnapshotEntity();
        task.setTaskId(command.taskId());
        task.setSessionId(command.sessionId());
        task.setPhysicalWorkerId(command.physicalWorkerId());
        task.setStateGeneration(command.workerIdentity().stateGeneration());
        task.setInstanceEpoch(command.workerIdentity().instanceEpoch());
        task.setProviderTaskId(null);
        task.setDispatchId(command.dispatchId());
        task.setOperationId(command.dispatchId());
        task.setSafeBindingDigestVersion(command.bindingDigestVersion());
        task.setSafeBindingDigest(command.bindingDigest());
        task.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        task.setCanonicalPhase(TaskCanonicalPhase.OPEN.name());
        task.setAvailability(LifecycleAvailability.READY.name());
        task.setConflictState(LifecycleConflictState.NONE.name());
        task.setCleanupState(TaskCleanupState.NOT_REQUIRED.name());
        task.setFactCursor(0L);
        task.setPolicyVersion(POLICY);
        task.setWriterGenerationId(target.getGenerationId());
        task.setSnapshotJson("{}");
        tasks.save(task);

        String workerReference = writerProofs.acquireReference(
                target.getProofId(), ProofAggregateType.WORKER,
                command.physicalWorkerId(), now);
        String sessionReference = writerProofs.acquireReference(
                target.getProofId(), ProofAggregateType.SESSION,
                command.sessionId(), now);
        String taskReference = writerProofs.acquireReference(
                target.getProofId(), ProofAggregateType.TASK,
                command.taskId(), now);

        LifecycleFactEntity initial = initialFact(command);
        facts.save(initial);

        LifecycleEffectOutboxEntity effect = initialEffect(
                target, command, taskReference);
        outbox.saveAndFlush(effect);
        WriterExclusivityProofService.EffectAuthorization authorization =
                writerProofs.authorizeEffect(
                        new WriterExclusivityProofService
                                .EffectAuthorizationCommand(
                                effect.getEffectId(), target.getProofId(),
                                taskReference, ProofAggregateType.TASK,
                                command.taskId(), target.getGenerationId(),
                                target.getControllerInventoryDigest(),
                                command.bindingDigest(),
                                workerReference, command.physicalWorkerId(),
                                sessionReference, command.sessionId()),
                        now);
        if (!authorization.providerCallAuthorized()) {
            throw denied(authorization.safeReasonCode());
        }
        target.setStatus("ADMITTED");
        target.setSafeReasonCode(null);
        authority.targetRepository().save(target);
        return new ProviderEffectAuthorization(
                true, true, false, effect.getEffectId(),
                command.dispatchId(), command.bindingDigestVersion(),
                command.bindingDigest(), "EFFECT_AUTHORIZED");
    }

    private void observeAcceptedLocked(AcceptedDisposition value) {
        LocalDateTime now = clock.databaseNow();
        var loaded = authority.loadVerifiedArtifacts(now);
        var target = authority.targetRepository()
                .findForUpdate(authority.properties().getExactTargetId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.TARGET_NOT_REGISTERED));
        authority.requireTargetAuthorityLocked(
                target, loaded, now, Set.of("ADMITTED", "CONSUMED"));
        if (!Objects.equals(target.getReservedTaskId(), value.taskId())
                || !Objects.equals(target.getReservedSessionId(),
                value.sessionId())
                || !target.getPhysicalWorkerId().equals(
                value.workerIdentity().physicalWorkerId())) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
        TaskLifecycleSnapshotEntity task = tasks
                .findForUpdate(value.taskId())
                .orElseThrow(() -> denied(LifecycleActivationReason
                        .ADMISSION_BINDING_MISMATCH));
        if (!LifecycleOwnershipMode.ENFORCED.name().equals(
                task.getOwnershipMode())
                || !Objects.equals(task.getDispatchId(), value.dispatchId())
                || !Objects.equals(task.getSafeBindingDigestVersion(),
                value.bindingDigestVersion())
                || !Objects.equals(task.getSafeBindingDigest(),
                value.bindingDigest())
                || !Objects.equals(task.getStateGeneration(),
                value.workerIdentity().stateGeneration())
                || !Objects.equals(task.getInstanceEpoch(),
                value.workerIdentity().instanceEpoch())
                || (task.getProviderTaskId() != null
                && !task.getProviderTaskId().equals(value.providerTaskId()))) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
        task.setProviderTaskId(value.providerTaskId());
        task.setFactCursor(Math.max(task.getFactCursor(),
                value.dispositionVersion()));
        tasks.save(task);

        LifecycleEffectOutboxEntity effect = outbox.findForUpdate(
                        initialEffectId(value.taskId()))
                .orElseThrow(() -> denied(LifecycleActivationReason
                        .ADMISSION_BINDING_MISMATCH));
        if (!"EFFECT_STARTED".equals(effect.getEffectState())
                || !Objects.equals(effect.getDispatchId(), value.dispatchId())
                || !Objects.equals(effect.getBindingDigest(),
                value.bindingDigest())) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
        effect.setProviderTaskId(value.providerTaskId());
        effect.setEffectState("COMPLETED");
        outbox.save(effect);

        String factId = stableId("activation-dispatched", value.taskId());
        if (!facts.existsById(factId)) {
            LifecycleFactEntity dispatched = new LifecycleFactEntity();
            dispatched.setFactId(factId);
            dispatched.setFactType(
                    TaskLifecycleFactType.TASK_DISPATCHED.name());
            dispatched.setSchemaVersion(1);
            dispatched.setAggregateType("TASK");
            dispatched.setAggregateId(value.taskId());
            dispatched.setTaskId(value.taskId());
            dispatched.setSessionId(value.sessionId());
            dispatched.setOperationId(value.dispatchId());
            dispatched.setPhysicalWorkerId(
                    value.workerIdentity().physicalWorkerId());
            dispatched.setStateGeneration(
                    value.workerIdentity().stateGeneration());
            dispatched.setInstanceEpoch(
                    value.workerIdentity().instanceEpoch());
            dispatched.setProviderTaskId(value.providerTaskId());
            dispatched.setDispatchId(value.dispatchId());
            dispatched.setSafeBindingDigestVersion(
                    value.bindingDigestVersion());
            dispatched.setSafeBindingDigest(value.bindingDigest());
            dispatched.setOwnershipMode(
                    LifecycleOwnershipMode.ENFORCED.name());
            dispatched.setSourceSequence(value.dispositionVersion());
            dispatched.setIdempotencyKey(factId);
            dispatched.setSafeReasonCode("TASK_DISPATCH_ACCEPTED");
            dispatched.setContentFreePayloadJson(json(TaskLifecycleFact.of(
                    factId,
                    TaskLifecycleFactType.TASK_DISPATCHED,
                    value.dispositionVersion())));
            facts.save(dispatched);
        }
        target.setStatus("CONSUMED");
        target.setLastObservedAt(now);
        authority.targetRepository().save(target);
    }

    private void requireExactRequest(
            ProductionAdmissionRequest request,
            LifecycleActivationManifest manifest,
            String targetStatus) {
        LifecycleActivationManifest.ExactTuple exact = manifest.exactTuple();
        if (!Objects.equals(exact.providerType(), request.providerType())) {
            throw denied(LifecycleActivationReason.PROVIDER_NOT_ALLOWLISTED);
        }
        if (!Objects.equals(exact.tenantId(), request.tenantId())
                || !Objects.equals(exact.userId(), request.userId())
                || !Objects.equals(exact.physicalWorkerId(),
                request.physicalWorkerId())
                || !Objects.equals(exact.modelConfigId(),
                request.modelConfigId())
                || !CodexModelCanonicalizer.matchesPhysicalTuple(
                request.providerType(), exact.model(), request.model())
                || !Objects.equals(exact.codexHomeKey(),
                request.codexHomeKey())) {
            throw denied(LifecycleActivationReason.EXACT_TUPLE_MISMATCH);
        }
        if (request.existingSessionId() != null) {
            throw denied(LifecycleActivationReason.NEW_SESSION_REQUIRED);
        }
        if (!Objects.equals(exact.promptSha256(), request.promptSha256())) {
            throw denied(LifecycleActivationReason.STATIC_PROMPT_MISMATCH);
        }
        if ((request.businessRuntimeContext() != null
                && !request.businessRuntimeContext().isEmpty())
                || (request.additionalDirectories() != null
                && !request.additionalDirectories().isEmpty())
                || Boolean.TRUE.equals(request.networkAccessEnabled())
                || (request.webSearchMode() != null
                && !"disabled".equalsIgnoreCase(request.webSearchMode()))
                || (request.developerInstructions() != null
                && !request.developerInstructions().isBlank())
                || !Objects.equals(manifest.target().workdir(),
                request.workdir())) {
            throw denied(LifecycleActivationReason.BUSINESS_ACCESS_FORBIDDEN);
        }
    }

    private void requireReservedBinding(
            com.foggy.navigator.session.lifecycle.persistence
                    .LifecycleActivationTargetEntity target,
            ProviderEffectCommand command) {
        if (!Objects.equals(target.getReservedTaskId(), command.taskId())
                || !Objects.equals(target.getReservedSessionId(),
                command.sessionId())
                || !Objects.equals(target.getPhysicalWorkerId(),
                command.physicalWorkerId())
                || !Objects.equals(target.getWorkerStateGeneration(),
                command.workerIdentity().stateGeneration())
                || !Objects.equals(target.getWorkerInstanceEpoch(),
                command.workerIdentity().instanceEpoch())
                || command.bindingDigest() == null
                || command.bindingDigest().isBlank()
                || !"JCS_SHA256_V1".equals(
                command.bindingDigestVersion())) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
    }

    private void requireCanonicalBinding(
            com.foggy.navigator.session.lifecycle.persistence
                    .LifecycleActivationTargetEntity target,
            SessionTaskEntity canonical) {
        if (!Objects.equals(target.getReservedSessionId(),
                canonical.getSessionId())
                || !Objects.equals(target.getProviderType(),
                canonical.getProviderType())
                || !Objects.equals(target.getPhysicalWorkerId(),
                canonical.getWorkerId())
                || !Objects.equals(target.getTenantId(),
                canonical.getTenantId())
                || !Objects.equals(target.getUserId(), canonical.getUserId())
                || !Objects.equals(target.getModelConfigId(),
                canonical.getModelConfigId())
                || !Objects.equals(target.getModel(), canonical.getModel())) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
    }

    private LifecycleFactEntity initialFact(ProviderEffectCommand command) {
        LifecycleFactEntity fact = new LifecycleFactEntity();
        String factId = stableId("activation-reserved", command.taskId());
        fact.setFactId(factId);
        fact.setFactType(TaskLifecycleFactType.TASK_DISPATCH_RESERVED.name());
        fact.setSchemaVersion(1);
        fact.setAggregateType("TASK");
        fact.setAggregateId(command.taskId());
        fact.setTaskId(command.taskId());
        fact.setSessionId(command.sessionId());
        fact.setOperationId(command.dispatchId());
        fact.setPhysicalWorkerId(command.physicalWorkerId());
        fact.setStateGeneration(command.workerIdentity().stateGeneration());
        fact.setInstanceEpoch(command.workerIdentity().instanceEpoch());
        fact.setDispatchId(command.dispatchId());
        fact.setSafeBindingDigestVersion(command.bindingDigestVersion());
        fact.setSafeBindingDigest(command.bindingDigest());
        fact.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        fact.setSourceSequence(0L);
        fact.setIdempotencyKey(factId);
        fact.setSafeReasonCode("TASK_DISPATCH_DURABLY_RESERVED");
        fact.setContentFreePayloadJson(json(TaskLifecycleFact.of(
                factId, TaskLifecycleFactType.TASK_DISPATCH_RESERVED, 0L)));
        return fact;
    }

    private LifecycleEffectOutboxEntity initialEffect(
            com.foggy.navigator.session.lifecycle.persistence
                    .LifecycleActivationTargetEntity target,
            ProviderEffectCommand command,
            String taskReference) {
        LifecycleEffectOutboxEntity effect = new LifecycleEffectOutboxEntity();
        effect.setEffectId(initialEffectId(command.taskId()));
        effect.setAggregateType(ProofAggregateType.TASK.name());
        effect.setAggregateId(command.taskId());
        effect.setPhysicalWorkerId(command.physicalWorkerId());
        effect.setProviderType(target.getProviderType());
        effect.setProviderTaskId(null);
        effect.setDispatchId(command.dispatchId());
        effect.setOperationId(command.dispatchId());
        effect.setBindingDigestVersion(command.bindingDigestVersion());
        effect.setBindingDigest(command.bindingDigest());
        effect.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        effect.setStateGeneration(command.workerIdentity().stateGeneration());
        effect.setInstanceEpoch(command.workerIdentity().instanceEpoch());
        effect.setEffectClaim(command.bindingDigest());
        effect.setAggregateReferenceId(taskReference);
        effect.setWriterGenerationId(target.getGenerationId());
        effect.setControllerInventoryDigest(
                target.getControllerInventoryDigest());
        effect.setEffectType("TASK_CREATE_DISPATCH");
        effect.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        effect.setEffectState("PREPARED");
        effect.setIdempotencyKey(stableId(
                "activation-task-create", target.getTargetId()
                        + ":" + command.taskId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetId", target.getTargetId());
        payload.put("runId", target.getRunId());
        payload.put("taskId", command.taskId());
        payload.put("sessionId", command.sessionId());
        payload.put("physicalWorkerId", command.physicalWorkerId());
        payload.put("providerType", target.getProviderType());
        payload.put("dispatchId", command.dispatchId());
        effect.setContentFreePayloadJson(json(payload));
        return effect;
    }

    private String initialEffectId(String taskId) {
        return stableId("activation-effect", taskId);
    }

    private String stableId(String prefix, String value) {
        try {
            String digest = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
            return prefix + "-" + digest.substring(0, 48);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw denied(LifecycleActivationReason
                    .ADMISSION_BINDING_MISMATCH);
        }
    }

    private String required(String value, String reason) {
        if (value == null || value.isBlank()) throw denied(reason);
        return value;
    }

    private LifecycleActivationDeniedException denied(String reason) {
        return new LifecycleActivationDeniedException(reason);
    }

    public record ProductionAdmissionRequest(
            String providerType,
            String tenantId,
            String userId,
            String physicalWorkerId,
            String sessionId,
            String taskId,
            String modelConfigId,
            String model,
            String existingSessionId,
            String promptSha256,
            String codexHomeKey,
            String workdir,
            Map<String, Object> businessRuntimeContext,
            List<String> additionalDirectories,
            Boolean networkAccessEnabled,
            String webSearchMode,
            String developerInstructions) {
        public ProductionAdmissionRequest {
            businessRuntimeContext = businessRuntimeContext == null
                    ? Map.of() : Map.copyOf(businessRuntimeContext);
            additionalDirectories = additionalDirectories == null
                    ? List.of() : List.copyOf(additionalDirectories);
        }
    }

    public record ActivationReservation(
            boolean activationRequired,
            String targetId,
            String runId,
            String proofId,
            String generationId,
            String controllerInventoryDigest,
            String sessionId,
            String taskId,
            String safeReasonCode) {
        static ActivationReservation notRequired() {
            return new ActivationReservation(
                    false, null, null, null, null, null, null, null,
                    "LIFECYCLE_ACTIVATION_NOT_REQUESTED");
        }
    }

    public record ProviderEffectCommand(
            String taskId,
            String sessionId,
            String physicalWorkerId,
            WorkerLifecycleIdentity workerIdentity,
            String dispatchId,
            String bindingDigestVersion,
            String bindingDigest) {
    }

    public record ProviderEffectAuthorization(
            boolean activationRequired,
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            String effectId,
            String dispatchId,
            String bindingDigestVersion,
            String bindingDigest,
            String safeReasonCode) {
        static ProviderEffectAuthorization notRequired() {
            return new ProviderEffectAuthorization(
                    false, true, false, null, null, null, null,
                    "LIFECYCLE_ACTIVATION_NOT_REQUESTED");
        }
    }

    public record AcceptedDisposition(
            String taskId,
            String sessionId,
            WorkerLifecycleIdentity workerIdentity,
            String providerTaskId,
            String dispatchId,
            String bindingDigestVersion,
            String bindingDigest,
            long dispositionVersion) {
    }
}

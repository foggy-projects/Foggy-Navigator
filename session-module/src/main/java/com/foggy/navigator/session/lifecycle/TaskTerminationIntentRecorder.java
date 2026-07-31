package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.LocalDateTime;

@Component
public class TaskTerminationIntentRecorder implements RuntimeTerminationIntentPort {
    private final LifecycleEffectOutboxRepository outbox;
    private final TaskLifecycleSnapshotRepository snapshots;

    public TaskTerminationIntentRecorder(
            LifecycleEffectOutboxRepository outbox,
            TaskLifecycleSnapshotRepository snapshots) {
        this.outbox = outbox;
        this.snapshots = snapshots;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RuntimeTerminationDelivery recordIntent(RuntimeTerminationIntent intent) {
        requireIntent(intent);
        intent = bindOwnerOperation(intent);
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
        entity.setDispatchId(intent.operationId());
        entity.setOperationId(intent.operationId());
        entity.setBindingDigest(intent.bindingDigest());
        entity.setEffectClaim("PUBLIC_TERMINATION_COMPATIBILITY");
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
    @Transactional
    public RuntimeTerminationAuthorization authorizeEffect(String clientRequestId) {
        LifecycleEffectOutboxEntity entity = outbox.findByIdempotencyKey(
                        key(clientRequestId))
                .flatMap(value -> outbox.findForUpdate(value.getEffectId()))
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
        if (!"PREPARED".equals(entity.getEffectState())) {
            throw new IllegalStateException("TERMINATION_DELIVERY_STATE_INVALID");
        }
        entity.setEffectState("EFFECT_STARTED");
        entity.setAuthorizedAt(LocalDateTime.now());
        outbox.save(entity);
        return new RuntimeTerminationAuthorization(
                delivery(entity), true, false, false, "EFFECT_AUTHORIZED");
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

    private String key(String clientRequestId) {
        return "termination-intent:" + clientRequestId;
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
        required(intent.operationId(), "OPERATION_ID");
        required(intent.bindingDigest(), "BINDING_DIGEST");
    }

    private void requireSameIntent(
            LifecycleEffectOutboxEntity existing,
            RuntimeTerminationIntent intent) {
        if (!intent.taskId().equals(existing.getAggregateId())
                || !intent.providerType().equals(existing.getProviderType())
                || !intent.physicalWorkerId().equals(existing.getPhysicalWorkerId())
                || !intent.providerTaskId().equals(existing.getProviderTaskId())
                || !intent.operationId().equals(existing.getOperationId())
                || !intent.bindingDigest().equals(existing.getBindingDigest())) {
            throw new IllegalStateException(
                    "TERMINATION_DELIVERY_BINDING_MISMATCH");
        }
    }

    private RuntimeTerminationIntent bindOwnerOperation(
            RuntimeTerminationIntent intent) {
        var snapshot = snapshots.findForUpdate(intent.taskId()).orElse(null);
        if (snapshot == null) return intent;
        if ("ENFORCED".equals(snapshot.getOwnershipMode())
                && (!intent.sessionId().equals(snapshot.getSessionId())
                || !intent.physicalWorkerId()
                .equals(snapshot.getPhysicalWorkerId())
                || !intent.providerTaskId()
                .equals(snapshot.getProviderTaskId()))) {
            throw new IllegalStateException(
                    "TERMINATION_OWNER_BINDING_MISMATCH");
        }
        snapshot.setOperationId(intent.operationId());
        snapshots.save(snapshot);
        String ownerDigest = snapshot.getSafeBindingDigest();
        return new RuntimeTerminationIntent(
                intent.clientRequestId(), intent.taskId(), intent.sessionId(),
                intent.providerType(), intent.physicalWorkerId(),
                intent.providerTaskId(), intent.operationId(),
                ownerDigest == null || ownerDigest.isBlank()
                        ? intent.bindingDigest() : ownerDigest);
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "TERMINATION_" + field + "_REQUIRED");
        }
    }
}

package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleFactRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TaskLifecycleShadowService {

    private final LifecycleFactRepository factRepository;
    private final TaskLifecycleSnapshotRepository snapshotRepository;
    private final LifecycleEffectOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TaskLifecycleReducer reducer = new TaskLifecycleReducer();

    public TaskLifecycleShadowService(
            LifecycleFactRepository factRepository,
            TaskLifecycleSnapshotRepository snapshotRepository,
            LifecycleEffectOutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.factRepository = factRepository;
        this.snapshotRepository = snapshotRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskLifecycleDecision ingest(
            String taskId,
            TaskLifecycleFact fact,
            String policyVersion) {
        if (factRepository.existsById(fact.factId())) {
            return recompute(taskId, policyVersion);
        }
        factRepository.save(toEntity(taskId, fact));
        TaskLifecycleDecision decision = recompute(taskId, policyVersion);
        snapshotRepository.save(toEntity(decision.snapshot()));
        for (LifecycleEffect effect : decision.requiredEffects()) {
            if (!effect.executionSuppressed()) {
                throw new IllegalStateException("SHADOW_OWNER_EFFECT_MUST_BE_SUPPRESSED");
            }
            String effectId = UUID.nameUUIDFromBytes(effect.idempotencyKey().getBytes()).toString();
            if (!outboxRepository.existsById(effectId)) {
                outboxRepository.save(toEntity(effect, effectId));
            }
        }
        return decision;
    }

    private TaskLifecycleDecision recompute(String taskId, String policyVersion) {
        List<TaskLifecycleFact> facts = factRepository
                .findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc("TASK", taskId)
                .stream()
                .map(this::fromEntity)
                .toList();
        return reducer.recompute(taskId, facts, java.util.Set.of(), policyVersion, facts.size());
    }

    private LifecycleFactEntity toEntity(String taskId, TaskLifecycleFact fact) {
        LifecycleFactEntity entity = new LifecycleFactEntity();
        entity.setFactId(fact.factId());
        entity.setFactType(fact.type().name());
        entity.setSchemaVersion(1);
        entity.setAggregateType("TASK");
        entity.setAggregateId(taskId);
        entity.setTaskId(taskId);
        entity.setOwnershipMode(LifecycleOwnershipMode.SHADOW.name());
        entity.setSourceSequence(fact.sourceSequence());
        entity.setIdempotencyKey("task:" + taskId + ":fact:" + fact.factId());
        entity.setSafeReasonCode(fact.type().name());
        entity.setContentFreePayloadJson(json(fact));
        return entity;
    }

    private TaskLifecycleFact fromEntity(LifecycleFactEntity entity) {
        try {
            return objectMapper.readValue(
                    entityContent(entity), TaskLifecycleFact.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LIFECYCLE_FACT_PAYLOAD_INVALID", e);
        }
    }

    private String entityContent(LifecycleFactEntity entity) {
        return entity.getContentFreePayloadJson();
    }

    private TaskLifecycleSnapshotEntity toEntity(TaskLifecycleSnapshot snapshot) {
        TaskLifecycleSnapshotEntity entity = new TaskLifecycleSnapshotEntity();
        entity.setTaskId(snapshot.taskId());
        entity.setOwnershipMode(snapshot.ownershipMode().name());
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
        return entity;
    }

    private LifecycleEffectOutboxEntity toEntity(LifecycleEffect effect, String effectId) {
        LifecycleEffectOutboxEntity entity = new LifecycleEffectOutboxEntity();
        entity.setEffectId(effectId);
        entity.setAggregateType("TASK");
        entity.setAggregateId(effect.aggregateId());
        entity.setEffectType(effect.effectType());
        entity.setEffectClass("LOCAL_IDEMPOTENT");
        entity.setEffectState("PROPOSED");
        entity.setIdempotencyKey(effect.idempotencyKey());
        entity.setContentFreePayloadJson("{}");
        return entity;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LIFECYCLE_CONTENT_FREE_SERIALIZATION_FAILED", e);
        }
    }
}

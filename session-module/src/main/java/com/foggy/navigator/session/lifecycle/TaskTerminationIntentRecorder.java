package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class TaskTerminationIntentRecorder implements RuntimeTerminationIntentPort {
    private final LifecycleEffectOutboxRepository outbox;

    public TaskTerminationIntentRecorder(LifecycleEffectOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIntent(
            String clientRequestId,
            String taskId,
            String providerType,
            String physicalWorkerId) {
        String key = "termination-intent:" + clientRequestId;
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        if (outbox.existsById(id)) return;
        LifecycleEffectOutboxEntity entity = new LifecycleEffectOutboxEntity();
        entity.setEffectId(id);
        entity.setAggregateId(taskId);
        entity.setEffectType("TERMINATION_REQUEST");
        entity.setEffectClass("PROVIDER_SIDE_EFFECT");
        entity.setEffectState("PROPOSED");
        entity.setIdempotencyKey(key);
        entity.setContentFreePayloadJson(
                "{\"ownershipMode\":\"SHADOW\",\"executionSuppressed\":true}");
        outbox.save(entity);
    }
}

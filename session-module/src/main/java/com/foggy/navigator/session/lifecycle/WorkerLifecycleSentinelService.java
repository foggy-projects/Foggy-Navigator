package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.springframework.stereotype.Service;

@Service
public class WorkerLifecycleSentinelService {
    private final WorkerLifecycleSentinel sentinel;
    private final WorkerLifecycleSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;
    private final TaskLifecycleOwnerService owner;

    public WorkerLifecycleSentinelService(
            JpaSentinelLeaseStore leases,
            WorkerLifecycleSnapshotRepository snapshots,
            ObjectMapper objectMapper,
            TaskLifecycleOwnerService owner) {
        this.sentinel = new WorkerLifecycleSentinel(
                "navigator-shadow-sentinel-" + java.util.UUID.randomUUID(),
                leases,
                java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.owner = owner;
    }

    public SentinelReconcileResult reconcile(
            String physicalWorkerId,
            SentinelTrigger trigger,
            WorkerLifecyclePort port) {
        WorkerLifecycleSnapshotEntity prior = snapshots.findById(physicalWorkerId)
                .orElse(null);
        if (prior != null && prior.getStateGeneration() != null
                && prior.getInstanceEpoch() != null) {
            sentinel.seedCursor(
                    physicalWorkerId,
                    new WorkerLifecycleIdentity(
                            physicalWorkerId,
                            prior.getStateGeneration(),
                            prior.getInstanceEpoch()),
                    prior.getFactCursor());
        }
        SentinelReconcileResult result =
                sentinel.reconcile(physicalWorkerId, trigger, port,
                        inventory -> ingestBeforeAck(inventory, prior));
        WorkerLifecycleSnapshotEntity snapshot = new WorkerLifecycleSnapshotEntity();
        snapshot.setPhysicalWorkerId(physicalWorkerId);
        snapshot.setOwnershipMode(result.state() == SentinelReconcileState.READY
                && result.facts().stream().anyMatch(fact ->
                fact.ownershipMode() == LifecycleOwnershipMode.ENFORCED)
                ? "ENFORCED" : "SHADOW");
        snapshot.setStateGeneration(result.identity() == null
                ? null : result.identity().stateGeneration());
        snapshot.setInstanceEpoch(result.identity() == null
                ? null : result.identity().instanceEpoch());
        snapshot.setAvailability(result.state() == SentinelReconcileState.READY
                ? LifecycleAvailability.READY.name()
                : LifecycleAvailability.OFFLINE_FROZEN.name());
        snapshot.setConflictState(LifecycleConflictState.NONE.name());
        snapshot.setFactCursor(result.throughSequence());
        snapshot.setPolicyVersion("ARCH-001-MVP-A");
        snapshot.setSnapshotJson(json(result));
        snapshots.save(snapshot);
        return result;
    }

    private void ingestBeforeAck(
            WorkerLifecycleSnapshot inventory,
            WorkerLifecycleSnapshotEntity prior) {
        String writerGenerationId = prior == null
                ? null : prior.getWriterGenerationId();
        for (var task : inventory.tasks()) {
            owner.enrollInventoryTask(
                    inventory.identity(), task, writerGenerationId);
        }
        inventory.facts().stream()
                .filter(fact -> "TASK".equals(fact.aggregateType()))
                .collect(java.util.stream.Collectors.groupingBy(
                        com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact::taskId))
                .forEach(owner::ingestNormalizedBatch);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_CONTENT_FREE_SERIALIZATION_FAILED", error);
        }
    }
}

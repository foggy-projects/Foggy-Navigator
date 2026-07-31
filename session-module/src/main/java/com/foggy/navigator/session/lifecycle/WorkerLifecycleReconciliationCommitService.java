package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

/**
 * Commits one fenced inventory/event coverage batch before the Worker ACK.
 */
@Service
public class WorkerLifecycleReconciliationCommitService {
    private final WorkerLifecycleSnapshotRepository snapshots;
    private final TaskLifecycleOwnerService owner;
    private final ObjectMapper objectMapper;

    public WorkerLifecycleReconciliationCommitService(
            WorkerLifecycleSnapshotRepository snapshots,
            TaskLifecycleOwnerService owner,
            ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.owner = owner;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void commit(
            WorkerLifecycleSnapshot inventory,
            WorkerLifecycleSnapshotEntity observedPrior) {
        WorkerLifecycleSnapshotEntity current = snapshots
                .findForUpdate(inventory.identity().physicalWorkerId())
                .orElse(null);
        boolean bootstrap = current == null && observedPrior == null;
        if (bootstrap && (inventory.tasks().stream().anyMatch(task ->
                task.ownershipMode() == LifecycleOwnershipMode.ENFORCED)
                || inventory.facts().stream().anyMatch(fact ->
                fact.ownershipMode() == LifecycleOwnershipMode.ENFORCED))) {
            throw new IllegalStateException(
                    "LIFECYCLE_ENFORCED_BOOTSTRAP_PROHIBITED");
        }
        String writerGenerationId = current == null
                ? null : current.getWriterGenerationId();
        for (var task : inventory.tasks()) {
            owner.enrollInventoryTask(
                    inventory.identity(), task, writerGenerationId);
        }
        inventory.facts().stream()
                .filter(fact -> "TASK".equals(fact.aggregateType()))
                .filter(fact -> fact.taskId() != null)
                .collect(Collectors.groupingBy(
                        com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact::taskId))
                .forEach(owner::ingestNormalizedBatch);

        WorkerLifecycleSnapshotEntity checkpoint =
                current == null ? new WorkerLifecycleSnapshotEntity() : current;
        checkpoint.setPhysicalWorkerId(
                inventory.identity().physicalWorkerId());
        if (current == null) {
            checkpoint.setOwnershipMode(LifecycleOwnershipMode.SHADOW.name());
        }
        checkpoint.setStateGeneration(inventory.identity().stateGeneration());
        checkpoint.setInstanceEpoch(inventory.identity().instanceEpoch());
        checkpoint.setAvailability(LifecycleAvailability.READY.name());
        checkpoint.setConflictState(LifecycleConflictState.NONE.name());
        checkpoint.setFactCursor(inventory.throughSequence());
        checkpoint.setPolicyVersion("ARCH-001-MVP-A");
        checkpoint.setSnapshotJson(json(inventory));
        snapshots.save(checkpoint);
    }

    @Transactional
    public void recordBlocked(
            String physicalWorkerId,
            SentinelReconcileResult result) {
        WorkerLifecycleSnapshotEntity snapshot = snapshots
                .findForUpdate(physicalWorkerId)
                .orElseGet(WorkerLifecycleSnapshotEntity::new);
        snapshot.setPhysicalWorkerId(physicalWorkerId);
        if (snapshot.getOwnershipMode() == null) {
            snapshot.setOwnershipMode(LifecycleOwnershipMode.SHADOW.name());
        }
        if (result.identity() != null) {
            snapshot.setStateGeneration(result.identity().stateGeneration());
            snapshot.setInstanceEpoch(result.identity().instanceEpoch());
        }
        boolean stateLoss = result.state() == SentinelReconcileState.STATE_GENERATION_RESET
                || result.state() == SentinelReconcileState.COVERAGE_GAP;
        snapshot.setAvailability(stateLoss
                ? LifecycleAvailability.AUTHORITY_QUARANTINED.name()
                : LifecycleAvailability.OFFLINE_FROZEN.name());
        snapshot.setConflictState(stateLoss
                ? LifecycleConflictState.WORKER_STATE_LOSS.name()
                : LifecycleConflictState.NONE.name());
        snapshot.setPolicyVersion("ARCH-001-MVP-A");
        snapshot.setSnapshotJson(json(result));
        snapshots.save(snapshot);
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

package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Commits one fenced inventory/event coverage batch before the Worker ACK.
 */
@Service
public class WorkerLifecycleReconciliationCommitService {
    private final WorkerLifecycleSnapshotRepository snapshots;
    private final TaskLifecycleOwnerService owner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public WorkerLifecycleReconciliationCommitService(
            WorkerLifecycleSnapshotRepository snapshots,
            TaskLifecycleOwnerService owner,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.snapshots = snapshots;
        this.owner = owner;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void commit(
            WorkerLifecycleSnapshot inventory,
            WorkerLifecycleSnapshotEntity observedPrior) {
        WorkerLifecycleSnapshotEntity current = transactions.execute(status ->
                snapshots.findForUpdate(
                        inventory.identity().physicalWorkerId())
                        .orElse(null));
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
        Map<String, List<com.foggy.navigator.spi.lifecycle
                .NormalizedLifecycleFact>> byTask = inventory.facts().stream()
                .filter(fact -> "TASK".equals(fact.aggregateType()))
                .filter(fact -> fact.taskId() != null)
                .collect(Collectors.groupingBy(
                        com.foggy.navigator.spi.lifecycle
                                .NormalizedLifecycleFact::taskId));
        for (var entry : byTask.entrySet()) {
            List<com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact>
                    taskFacts = entry.getValue();
            for (int offset = 0; offset < taskFacts.size(); offset += 50) {
                owner.ingestNormalizedBatch(
                        entry.getKey(),
                        taskFacts.subList(offset,
                                Math.min(offset + 50, taskFacts.size())));
            }
        }

        transactions.executeWithoutResult(status -> {
            WorkerLifecycleSnapshotEntity checkpoint = snapshots
                    .findForUpdate(inventory.identity().physicalWorkerId())
                    .orElseGet(WorkerLifecycleSnapshotEntity::new);
            checkpoint.setPhysicalWorkerId(
                    inventory.identity().physicalWorkerId());
            if (checkpoint.getOwnershipMode() == null) {
                checkpoint.setOwnershipMode(
                        LifecycleOwnershipMode.SHADOW.name());
            }
            checkpoint.setStateGeneration(
                    inventory.identity().stateGeneration());
            checkpoint.setInstanceEpoch(
                    inventory.identity().instanceEpoch());
            applyOperationalState(checkpoint, Set.of());
            checkpoint.setFactCursor(inventory.throughSequence());
            checkpoint.setPolicyVersion("ARCH-001-MVP-A");
            checkpoint.setSnapshotJson(json(inventory));
            snapshots.save(checkpoint);
        });
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
        applyOperationalState(snapshot, Set.of(stateLoss
                ? LifecycleBlocker.WORKER_STATE_LOSS
                : LifecycleBlocker.WORKER_OFFLINE));
        snapshot.setPolicyVersion("ARCH-001-MVP-A");
        snapshot.setSnapshotJson(json(result));
        snapshots.save(snapshot);
    }

    private void applyOperationalState(
            WorkerLifecycleSnapshotEntity snapshot,
            Set<LifecycleBlocker> observations) {
        LifecycleConflictState retained = snapshot.getConflictState() == null
                ? LifecycleConflictState.NONE
                : LifecycleConflictState.valueOf(snapshot.getConflictState());
        LifecycleOperationalState operational =
                LifecycleOperationalReducer.reduceRetainingConflict(
                        retained, observations);
        snapshot.setAvailability(operational.availability().name());
        snapshot.setConflictState(operational.conflictState().name());
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

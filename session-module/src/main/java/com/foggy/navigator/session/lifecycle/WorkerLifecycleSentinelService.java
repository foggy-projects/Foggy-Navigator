package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WorkerLifecycleSentinelService {
    private final WorkerLifecycleSentinel sentinel;
    private final WorkerLifecycleSnapshotRepository snapshots;
    private final WorkerLifecycleReconciliationCommitService commits;

    public WorkerLifecycleSentinelService(
            SentinelLeaseStore leases,
            WorkerLifecycleSnapshotRepository snapshots,
            WorkerLifecycleReconciliationCommitService commits) {
        this.sentinel = new WorkerLifecycleSentinel(
                "navigator-shadow-sentinel-" + java.util.UUID.randomUUID(),
                leases,
                java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));
        this.snapshots = snapshots;
        this.commits = commits;
    }

    public SentinelReconcileResult reconcile(
            String physicalWorkerId,
            SentinelTrigger trigger,
            WorkerLifecyclePort port) {
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "WORKER_LIFECYCLE_CALL_INSIDE_DATABASE_TRANSACTION");
        }
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
                        inventory -> commits.commit(inventory, prior));
        if (result.state() != SentinelReconcileState.READY) {
            commits.recordBlocked(physicalWorkerId, result);
        }
        return result;
    }
}

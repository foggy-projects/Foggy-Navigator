package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import org.springframework.stereotype.Service;

@Service
public class WorkerLifecycleSentinelService {
    private final WorkerLifecycleSentinel sentinel;
    private final WorkerLifecycleSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    public WorkerLifecycleSentinelService(
            JpaSentinelLeaseStore leases,
            WorkerLifecycleSnapshotRepository snapshots,
            ObjectMapper objectMapper) {
        this.sentinel = new WorkerLifecycleSentinel(
                "navigator-shadow-sentinel-" + java.util.UUID.randomUUID(),
                leases,
                java.time.Clock.systemUTC(),
                java.time.Duration.ofSeconds(30));
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
    }

    public SentinelReconcileResult reconcile(
            String physicalWorkerId,
            SentinelTrigger trigger,
            WorkerLifecyclePort port) {
        SentinelReconcileResult result =
                sentinel.reconcile(physicalWorkerId, trigger, port);
        WorkerLifecycleSnapshotEntity snapshot = new WorkerLifecycleSnapshotEntity();
        snapshot.setPhysicalWorkerId(physicalWorkerId);
        snapshot.setOwnershipMode("SHADOW");
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_CONTENT_FREE_SERIALIZATION_FAILED", error);
        }
    }
}

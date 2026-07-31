package com.foggy.navigator.spi.lifecycle;

public interface WorkerLifecyclePort {
    default String physicalWorkerId() {
        throw new UnsupportedOperationException("LIFECYCLE_WORKER_ID_UNAVAILABLE");
    }

    WorkerLifecycleReadiness probe(String physicalWorkerId);

    WorkerLifecycleSnapshot inventory(
            WorkerLifecycleIdentity expectedIdentity,
            long afterSequence);

    default WorkerLifecycleSnapshot events(
            WorkerLifecycleIdentity expectedIdentity,
            long afterSequence) {
        return inventory(expectedIdentity, afterSequence);
    }

    long acknowledge(WorkerLifecycleIdentity expectedIdentity, long throughSequence);

    default WorkerLifecycleDispatchStatus dispatchStatus(
            WorkerLifecycleIdentity expectedIdentity,
            LifecycleOwnershipMode expectedMode,
            String dispatchId,
            String safeBindingDigestVersion,
            String safeBindingDigest) {
        throw new UnsupportedOperationException("LIFECYCLE_DISPATCH_STATUS_UNAVAILABLE");
    }
}

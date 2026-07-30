package com.foggy.navigator.spi.lifecycle;

public interface WorkerLifecyclePort {
    WorkerLifecycleReadiness probe(String physicalWorkerId);

    WorkerLifecycleSnapshot inventory(
            WorkerLifecycleIdentity expectedIdentity,
            long afterSequence);

    long acknowledge(WorkerLifecycleIdentity expectedIdentity, long throughSequence);
}

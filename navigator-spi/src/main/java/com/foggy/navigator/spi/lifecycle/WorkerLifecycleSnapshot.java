package com.foggy.navigator.spi.lifecycle;

import java.util.List;

public record WorkerLifecycleSnapshot(
        WorkerLifecycleIdentity identity,
        long minAvailableSequence,
        long throughSequence,
        boolean completeActiveTaskSet,
        List<WorkerLifecycleTask> tasks,
        List<NormalizedLifecycleFact> facts
) {
    public WorkerLifecycleSnapshot {
        tasks = List.copyOf(tasks);
        facts = List.copyOf(facts);
    }
}

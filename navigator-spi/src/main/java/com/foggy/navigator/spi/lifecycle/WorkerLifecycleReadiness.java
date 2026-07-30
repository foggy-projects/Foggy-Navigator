package com.foggy.navigator.spi.lifecycle;

import java.util.List;
import java.util.Set;

public record WorkerLifecycleReadiness(
        boolean ready,
        WorkerLifecycleIdentity identity,
        Set<String> capabilities,
        List<String> reasonCodes
) {
    public WorkerLifecycleReadiness {
        capabilities = Set.copyOf(capabilities);
        reasonCodes = List.copyOf(reasonCodes);
    }
}

package com.foggy.navigator.spi.lifecycle;

import java.util.Optional;
import java.util.Set;

public interface WorkerLifecyclePortResolver {
    Optional<WorkerLifecyclePort> resolve(String physicalWorkerId);

    /**
     * Returns configured lifecycle-v1 runtimes that may be observed in SHADOW.
     * Discovery never grants ENFORCED ownership.
     */
    default Set<String> discoverShadowWorkers() {
        return Set.of();
    }
}

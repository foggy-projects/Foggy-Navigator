package com.foggy.navigator.spi.lifecycle;

import java.util.Optional;

public interface WorkerLifecyclePortResolver {
    Optional<WorkerLifecyclePort> resolve(String physicalWorkerId);
}

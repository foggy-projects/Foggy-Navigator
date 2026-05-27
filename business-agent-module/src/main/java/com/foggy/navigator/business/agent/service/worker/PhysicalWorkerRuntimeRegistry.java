package com.foggy.navigator.business.agent.service.worker;

import java.util.Optional;

public interface PhysicalWorkerRuntimeRegistry {

    Optional<ResolvedPhysicalWorker> resolve(String tenantId, String upstreamSystemId, String workerId);
}

package com.foggy.navigator.business.agent.service.worker;

import com.foggy.navigator.common.enums.ResourceOwnerType;

public record ResolvedPhysicalWorker(
        String workerId,
        String workerBackend,
        ResourceOwnerType ownerType,
        String ownerId,
        String source) {
}

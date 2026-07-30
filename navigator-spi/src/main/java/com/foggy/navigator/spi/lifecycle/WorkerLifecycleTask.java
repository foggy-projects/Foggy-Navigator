package com.foggy.navigator.spi.lifecycle;

public record WorkerLifecycleTask(
        String navigatorTaskId,
        String providerTaskId,
        LifecycleOwnershipMode ownershipMode,
        String initialDispatchId,
        String safeBindingDigestVersion,
        String safeBindingDigest,
        String lifecycleState,
        long lastSequence
) {
}

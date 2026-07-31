package com.foggy.navigator.spi.lifecycle;

public record WorkerLifecycleDispatchStatus(
        WorkerLifecycleIdentity workerIdentity,
        LifecycleOwnershipMode ownershipMode,
        String navigatorTaskId,
        String providerTaskId,
        String dispatchId,
        String operationId,
        String safeBindingDigestVersion,
        String safeBindingDigest,
        String effectPhase,
        long dispositionVersion,
        boolean duplicate,
        boolean providerEffectStarted,
        boolean reconcileRequired
) {
}

package com.foggy.navigator.spi.lifecycle;

import java.time.Instant;

public record NormalizedLifecycleFact(
        String factId,
        String factType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        String sessionId,
        String taskId,
        String providerTaskId,
        String operationId,
        WorkerLifecycleIdentity workerIdentity,
        LifecycleOwnershipMode ownershipMode,
        String dispatchId,
        String safeBindingDigestVersion,
        String safeBindingDigest,
        long sourceSequence,
        String idempotencyKey,
        Instant observedAt,
        Instant recordedAt,
        String safeReasonCode,
        String terminalOutcome
) {
}

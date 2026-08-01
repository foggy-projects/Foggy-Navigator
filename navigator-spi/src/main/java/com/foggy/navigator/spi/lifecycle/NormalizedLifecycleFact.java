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
        String terminalOutcome,
        String acceptanceDisposition,
        String effectPhase,
        Boolean neverAcceptedProof,
        Long dispositionVersion
) {
    public NormalizedLifecycleFact(
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
            String terminalOutcome) {
        this(factId, factType, schemaVersion, aggregateType, aggregateId,
                sessionId, taskId, providerTaskId, operationId,
                workerIdentity, ownershipMode, dispatchId,
                safeBindingDigestVersion, safeBindingDigest,
                sourceSequence, idempotencyKey, observedAt, recordedAt,
                safeReasonCode, terminalOutcome, null, null, null, null);
    }
}

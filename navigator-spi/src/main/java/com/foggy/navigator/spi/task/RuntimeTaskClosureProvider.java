package com.foggy.navigator.spi.task;

/**
 * Provider-neutral, runtime-lane task closure contract.
 *
 * <p>The expected Worker id is an equality fence over the durable task binding;
 * it is never a routing input.</p>
 */
public interface RuntimeTaskClosureProvider {

    boolean supports(String providerType);

    TerminationReadiness inspect(String taskId, String expectedPhysicalWorkerId);

    TerminationResult terminate(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            String reason,
            String clientRequestId,
            boolean dryRun);

    /**
     * Persists provider-side admission state used to derive the exact
     * Worker-v1 command binding. It runs inside the public receipt transaction
     * and therefore must never perform Worker/HTTP/SSE calls.
     */
    default TerminationAdmission prepareTerminationAdmission(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            String reason,
            String clientRequestId) {
        return null;
    }

    ReconciliationResult reconcile(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount,
            String clientRequestId,
            boolean dryRun);

    record TerminationReadiness(
            boolean workerReachable,
            boolean workerActiveTaskPresent,
            boolean terminationReady,
            boolean terminationAuthConfigured,
            boolean terminationWorkerIdConfigured,
            boolean terminateAllowed,
            String blockedReason) {
    }

    record TerminationResult(
            boolean alreadyTerminal,
            boolean terminationDispatched,
            boolean idempotentReplay,
            boolean reconcileRequired,
            String providerStatus,
            String operationId,
            String sanitizedErrorCode) {
    }

    record TerminationAdmission(
            String operationId,
            String dispatchId,
            String ownershipMode,
            String stateGeneration,
            String instanceEpoch,
            String bindingDigestVersion,
            String bindingDigest) {
    }

    record ReconciliationResult(
            boolean reconciliationChanged,
            boolean alreadyConsistent,
            String providerStatus,
            String durableEvidence,
            String sanitizedErrorCode) {
    }
}

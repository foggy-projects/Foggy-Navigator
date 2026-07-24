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

    record ReconciliationResult(
            boolean reconciliationChanged,
            boolean alreadyConsistent,
            String providerStatus,
            String durableEvidence,
            String sanitizedErrorCode) {
    }
}

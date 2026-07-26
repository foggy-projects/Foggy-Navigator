package com.foggy.navigator.spi.task;

/**
 * Provider-neutral, read-only completion evidence observation.
 *
 * <p>Implementations must not dispatch, cancel, retry, recover, reconcile, or
 * read content-bearing prompt/result/event material.</p>
 */
public interface RuntimeTaskCompletionReadinessProvider {

    boolean supportsCompletionReadiness(String providerType);

    Observation inspectCompletionReadiness(
            String taskId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount);

    record Observation(
            Boolean workerReachable,
            String workerObservedAt,
            Boolean workerTaskKnown,
            String workerTaskState,
            Boolean providerProcessPresent,
            String providerProcessState,
            Boolean providerActiveTaskPresent,
            Boolean providerTaskTerminal,
            String providerTerminalStatus,
            String lastHeartbeatAt,
            String lastProgressAt,
            String processExitedAt,
            Boolean finalOutputPresent,
            Boolean finalOutputDurable,
            String finalOutputDigest,
            String finalOutputRecordedAt,
            Boolean structuredOutputPresent,
            String structuredOutputDigest,
            Boolean completionSignalPresent,
            String completionSignalSource,
            String completionSignalRecordedAt,
            Boolean resultRecoverable,
            String evidenceSchema,
            String providerTaskId,
            Integer receiptDispatchCount,
            Boolean identityVerified,
            String sanitizedErrorCode) {
    }
}

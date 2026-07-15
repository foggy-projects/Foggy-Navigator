package com.foggy.navigator.spi.agent;

import java.util.List;

/**
 * Narrow port for provider worker-session query operations.
 */
public interface WorkerSessionQueryProvider extends TaskProviderPort {

    /** List sessions on a worker using the typed internal contract. */
    default List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        throw new UnsupportedOperationException("listWorkerSessionSummaries not supported by " + getProviderType());
    }

    /** Count messages in a worker session using the typed internal contract. */
    default WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId, String userId) {
        throw new UnsupportedOperationException("getWorkerSessionMessageCountResult not supported by " + getProviderType());
    }

    /** Get paged worker-session messages using the typed internal contract. */
    default List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                 String userId, Integer offset, Integer limit) {
        throw new UnsupportedOperationException("listWorkerSessionMessages not supported by " + getProviderType());
    }

    /** Trigger worker session sync using the typed internal contract. */
    default WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        throw new UnsupportedOperationException("syncWorkerSessionState not supported by " + getProviderType());
    }
}

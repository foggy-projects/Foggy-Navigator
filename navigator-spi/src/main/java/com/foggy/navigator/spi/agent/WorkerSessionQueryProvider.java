package com.foggy.navigator.spi.agent;

import java.util.List;
import java.util.Map;

/**
 * Narrow port for provider worker-session query operations.
 */
public interface WorkerSessionQueryProvider extends TaskProviderPort {

    /** List sessions on a worker using the typed internal contract. */
    @SuppressWarnings("deprecation")
    default List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        return WorkerSessionSummary.fromList(listWorkerSessions(workerId, userId));
    }

    /** Count messages in a worker session using the typed internal contract. */
    @SuppressWarnings("deprecation")
    default WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId, String userId) {
        return WorkerSessionMessageCount.from(getWorkerSessionMessageCount(workerId, sessionId, userId));
    }

    /** Get paged worker-session messages using the typed internal contract. */
    @SuppressWarnings("deprecation")
    default List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                 String userId, Integer offset, Integer limit) {
        return WorkerSessionMessage.fromList(getWorkerSessionMessages(workerId, sessionId, userId, offset, limit));
    }

    /** Trigger worker session sync using the typed internal contract. */
    @SuppressWarnings("deprecation")
    default WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        return WorkerSessionSyncResult.from(syncWorkerSessions(workerId, userId, tenantId));
    }

    /**
     * Legacy REST-compatible map contract. Prefer overriding {@link #listWorkerSessionSummaries(String, String)}.
     *
     * @deprecated since 1.3.1, use {@link #listWorkerSessionSummaries(String, String)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        throw new UnsupportedOperationException("listWorkerSessions not supported by " + getProviderType());
    }

    /**
     * Legacy REST-compatible map contract. Prefer overriding {@link #getWorkerSessionMessageCountResult(String, String, String)}.
     *
     * @deprecated since 1.3.1, use {@link #getWorkerSessionMessageCountResult(String, String, String)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        throw new UnsupportedOperationException("getWorkerSessionMessageCount not supported by " + getProviderType());
    }

    /**
     * Legacy REST-compatible map contract. Prefer overriding {@link #listWorkerSessionMessages(String, String, String, Integer, Integer)}.
     *
     * @deprecated since 1.3.1, use {@link #listWorkerSessionMessages(String, String, String, Integer, Integer)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        throw new UnsupportedOperationException("getWorkerSessionMessages not supported by " + getProviderType());
    }

    /**
     * Legacy REST-compatible map contract. Prefer overriding {@link #syncWorkerSessionState(String, String, String)}.
     *
     * @deprecated since 1.3.1, use {@link #syncWorkerSessionState(String, String, String)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        throw new UnsupportedOperationException("syncWorkerSessions not supported by " + getProviderType());
    }
}

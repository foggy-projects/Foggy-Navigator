package com.foggy.navigator.spi.agent;

import java.util.List;
import java.util.Map;

/**
 * Narrow port for provider worker-session query operations.
 */
public interface WorkerSessionQueryProvider extends TaskProviderPort {

    /** List sessions on a worker. */
    default List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        throw new UnsupportedOperationException("listWorkerSessions not supported by " + getProviderType());
    }

    /** Count messages in a worker session. */
    default Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        throw new UnsupportedOperationException("getWorkerSessionMessageCount not supported by " + getProviderType());
    }

    /** Get paged worker-session messages. */
    default List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        throw new UnsupportedOperationException("getWorkerSessionMessages not supported by " + getProviderType());
    }

    /** Trigger worker session sync. */
    default Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        throw new UnsupportedOperationException("syncWorkerSessions not supported by " + getProviderType());
    }
}

package com.foggy.navigator.spi.config;

/**
 * Backend-specific Worker capability and connection probe.
 */
public interface WorkerBackendConnectionTester {

    String getWorkerBackend();

    /** Fast local capability check used by model authorization and filtering. */
    boolean supportsWorker(String workerId);

    /** Model-aware capability check. Implementations may apply runtime-specific constraints. */
    default boolean supportsWorker(String workerId, String modelName) {
        return supportsWorker(workerId);
    }

    /** Executes a real probe against the selected Worker backend. */
    String testConnection(String userId, String tenantId, String workerId, String modelName);
}

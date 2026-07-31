package com.foggy.navigator.spi.lifecycle;

/**
 * Releases lifecycle writer-proof references from real aggregate retirement
 * transitions. Implementations fail closed while an aggregate is active.
 */
public interface LifecycleEnrollmentRetirementPort {
    void taskCleanupCompleted(String taskId);

    void sessionClosed(String sessionId);

    void workerRetired(String physicalWorkerId);
}

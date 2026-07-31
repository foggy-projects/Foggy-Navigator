package com.foggy.navigator.spi.lifecycle;

import java.util.Optional;

public interface TaskLifecycleProjectionPort {
    Optional<TaskLifecycleProjection> find(String taskId);

    record TaskLifecycleProjection(
            String taskId,
            String canonicalTaskStatus,
            boolean canonicalTerminal,
            boolean cleanupComplete,
            String terminalOutcome,
            String terminalSource,
            String physicalWorkerId,
            String providerTaskId) {
        public boolean typedTerminal() {
            return canonicalTerminal
                    && cleanupComplete
                    && canonicalTaskStatus != null
                    && switch (canonicalTaskStatus) {
                case "COMPLETED", "FAILED", "ABORTED", "CANCELLED",
                     "REJECTED", "TIMED_OUT", "TIMEOUT" -> true;
                default -> false;
            };
        }
    }
}

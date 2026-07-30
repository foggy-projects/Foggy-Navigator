package com.foggy.navigator.spi.lifecycle;

public record WorkerLifecycleIdentity(
        String physicalWorkerId,
        String stateGeneration,
        String instanceEpoch
) {
    public WorkerLifecycleIdentity {
        require(physicalWorkerId, "physicalWorkerId");
        require(stateGeneration, "stateGeneration");
        require(instanceEpoch, "instanceEpoch");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LIFECYCLE_" + field + "_REQUIRED");
        }
    }
}

package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;

/**
 * Content-free identity envelope that binds a lifecycle fact to one exact
 * Navigator task and one exact physical Worker runtime generation.
 */
public record TaskLifecycleBinding(
        String sessionId,
        String physicalWorkerId,
        String stateGeneration,
        String instanceEpoch,
        LifecycleOwnershipMode ownershipMode,
        String dispatchId,
        String operationId,
        String bindingDigest,
        String providerTaskId
) {
    public TaskLifecycleBinding {
        require(sessionId, "SESSION_ID");
        require(physicalWorkerId, "PHYSICAL_WORKER_ID");
        require(stateGeneration, "STATE_GENERATION");
        require(instanceEpoch, "INSTANCE_EPOCH");
        if (ownershipMode == null) {
            throw new IllegalArgumentException("LIFECYCLE_OWNERSHIP_MODE_REQUIRED");
        }
        require(dispatchId, "DISPATCH_ID");
        require(operationId, "OPERATION_ID");
        require(bindingDigest, "BINDING_DIGEST");
        require(providerTaskId, "PROVIDER_TASK_ID");
    }

    public boolean exactRuntimeMatch(TaskLifecycleBinding other) {
        return other != null
                && physicalWorkerId.equals(other.physicalWorkerId)
                && stateGeneration.equals(other.stateGeneration)
                && instanceEpoch.equals(other.instanceEpoch)
                && ownershipMode == other.ownershipMode
                && dispatchId.equals(other.dispatchId)
                && operationId.equals(other.operationId)
                && bindingDigest.equals(other.bindingDigest)
                && providerTaskId.equals(other.providerTaskId)
                && sessionId.equals(other.sessionId);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LIFECYCLE_" + field + "_REQUIRED");
        }
    }
}

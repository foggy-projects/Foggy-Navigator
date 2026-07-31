package com.foggy.navigator.spi.lifecycle;

/**
 * Persists and authorizes one exact Worker lifecycle command. The PREPARED
 * record and EFFECT_STARTED authorization are deliberately separate commits:
 * PREPARED can be continued, while EFFECT_STARTED can only be reconciled.
 */
public interface WorkerLifecycleCommandAuthorizationPort {

    PreparedCommand prepare(WorkerLifecycleCommand command);

    Authorization authorize(String effectId);

    record WorkerLifecycleCommand(
            String taskId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String dispatchId,
            String operationId,
            String bindingDigest) {
    }

    record PreparedCommand(
            String effectId,
            String effectState,
            String bindingDigest) {
    }

    record Authorization(
            PreparedCommand command,
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            String safeReasonCode) {
    }
}

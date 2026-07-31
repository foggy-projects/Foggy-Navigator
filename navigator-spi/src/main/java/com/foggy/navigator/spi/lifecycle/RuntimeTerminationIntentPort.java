package com.foggy.navigator.spi.lifecycle;

/**
 * Participates in the public termination-receipt transaction. Implementations
 * may persist owner intent/outbox binding but must not call a provider.
 */
public interface RuntimeTerminationIntentPort {
    RuntimeTerminationDelivery recordIntent(
            RuntimeTerminationIntent intent);

    RuntimeTerminationDelivery find(String clientRequestId);

    RuntimeTerminationAuthorization authorizeEffect(String clientRequestId);

    void resultObserved(String clientRequestId, String safeResultCode);

    record RuntimeTerminationIntent(
            String clientRequestId,
            String taskId,
            String sessionId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String operationId,
            String bindingDigest) {
    }

    record RuntimeTerminationDelivery(
            String effectId,
            String clientRequestId,
            String taskId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String operationId,
            String bindingDigest,
            String effectState) {
    }

    record RuntimeTerminationAuthorization(
            RuntimeTerminationDelivery delivery,
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            boolean resultObserved,
            String safeReasonCode) {
    }
}

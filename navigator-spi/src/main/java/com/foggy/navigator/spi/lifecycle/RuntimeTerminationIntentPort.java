package com.foggy.navigator.spi.lifecycle;

import java.util.List;

/**
 * Participates in the public termination-receipt transaction. Implementations
 * may persist owner intent/outbox binding but must not call a provider.
 */
public interface RuntimeTerminationIntentPort {
    RuntimeTerminationDelivery recordIntent(
            RuntimeTerminationIntent intent);

    RuntimeTerminationDelivery find(String clientRequestId);

    default List<RuntimeTerminationDelivery> findPrepared(int limit) {
        return List.of();
    }

    RuntimeTerminationAuthorization authorizeEffect(String clientRequestId);

    void resultObserved(String clientRequestId, String safeResultCode);

    record RuntimeTerminationIntent(
            String clientRequestId,
            String taskId,
            String sessionId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String dispatchId,
            String operationId,
            String bindingDigest) {
        public RuntimeTerminationIntent(
                String clientRequestId,
                String taskId,
                String sessionId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String operationId,
                String bindingDigest) {
            this(clientRequestId, taskId, sessionId, providerType,
                    physicalWorkerId, providerTaskId, operationId,
                    operationId, bindingDigest);
        }
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

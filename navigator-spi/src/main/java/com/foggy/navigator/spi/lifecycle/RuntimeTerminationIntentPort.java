package com.foggy.navigator.spi.lifecycle;

/**
 * Participates in the public termination-receipt transaction. Implementations
 * may persist owner intent/outbox binding but must not call a provider.
 */
public interface RuntimeTerminationIntentPort {
    void recordIntent(
            String clientRequestId,
            String taskId,
            String providerType,
            String physicalWorkerId);
}

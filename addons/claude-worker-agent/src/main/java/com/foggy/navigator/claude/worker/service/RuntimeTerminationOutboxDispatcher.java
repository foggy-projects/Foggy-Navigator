package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.springframework.stereotype.Service;

/**
 * Idempotent handler for the durable termination effect outbox.
 *
 * <p>The caller may invoke a provider only when {@link #authorize(String)}
 * returns {@code providerCallAuthorized=true}. Redelivery at
 * {@code EFFECT_STARTED} or later is a read-only disposition and must never
 * execute a second provider effect.</p>
 */
@Service
public class RuntimeTerminationOutboxDispatcher {
    private final RuntimeTerminationAcceptanceCoordinator coordinator;

    public RuntimeTerminationOutboxDispatcher(
            RuntimeTerminationAcceptanceCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public RuntimeTerminationIntentPort.RuntimeTerminationAuthorization authorize(
            String clientRequestId) {
        return coordinator.authorize(clientRequestId);
    }

    public void resultObserved(
            String clientRequestId, String safeResultCode) {
        coordinator.resultObserved(clientRequestId, safeResultCode);
    }
}

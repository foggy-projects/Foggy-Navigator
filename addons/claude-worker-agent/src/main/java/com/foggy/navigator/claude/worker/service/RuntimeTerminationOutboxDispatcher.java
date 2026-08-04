package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

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
    private final List<RuntimeTaskClosureProvider> providers;

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeTerminationOutboxDispatcher(
            RuntimeTerminationAcceptanceCoordinator coordinator,
            List<RuntimeTaskClosureProvider> providers) {
        this.coordinator = coordinator;
        this.providers = List.copyOf(providers);
    }

    RuntimeTerminationOutboxDispatcher(
            RuntimeTerminationAcceptanceCoordinator coordinator) {
        this.coordinator = coordinator;
        this.providers = List.of();
    }

    public RuntimeTerminationIntentPort.RuntimeTerminationAuthorization authorize(
            String clientRequestId) {
        return coordinator.authorize(clientRequestId);
    }

    public void resultObserved(
            String clientRequestId, String safeResultCode) {
        coordinator.resultObserved(clientRequestId, safeResultCode);
    }

    boolean recoveryCapable() {
        return !providers.isEmpty();
    }

    /**
     * Durable-outbox dispatcher used both by the HTTP fast path and by the
     * restart recovery poller. Authorization returns the frozen effect
     * principal before this method invokes the provider; redelivery at
     * EFFECT_STARTED returns without a second effect.
     */
    public RuntimeTaskClosureProvider.TerminationResult dispatch(
            String clientRequestId, String safeReason) {
        var authorization = coordinator.authorize(clientRequestId);
        if (!authorization.providerCallAuthorized()) return null;
        var delivery = authorization.delivery();
        String ownerUserId = requiredPrincipal(
                delivery.ownerUserId(), "OWNER_USER_ID");
        String tenantId = requiredPrincipal(
                delivery.tenantId(), "TENANT_ID");
        RuntimeTaskClosureProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(
                        delivery.providerType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "RUNTIME_TASK_PROVIDER_UNSUPPORTED"));
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "PROVIDER_CALL_INSIDE_DATABASE_TRANSACTION");
        }
        RuntimeTaskClosureProvider.TerminationResult result =
                provider.terminate(
                        delivery.taskId(), ownerUserId,
                        tenantId, delivery.physicalWorkerId(),
                        safeReason, delivery.clientRequestId(), false);
        coordinator.resultObserved(
                clientRequestId,
                result.terminationDispatched()
                        ? "TERMINATION_DISPATCHED"
                        : result.alreadyTerminal()
                        ? "TASK_ALREADY_TERMINAL"
                        : "TERMINATION_RESULT_OBSERVED");
        return result;
    }

    @Scheduled(
            fixedDelayString =
                    "${navigator.lifecycle.termination-outbox-poll-ms:5000}",
            initialDelayString =
                    "${navigator.lifecycle.termination-outbox-initial-delay-ms:5000}")
    public void recoverPrepared() {
        if (providers.isEmpty()) return;
        for (var delivery : coordinator.prepared(25)) {
            try {
                dispatch(delivery.clientRequestId(),
                        "lifecycle-owner-recovery");
            } catch (RuntimeException ignored) {
                // PREPARED remains retryable. EFFECT_STARTED is deliberately
                // never re-invoked when a provider response was lost.
            }
        }
    }

    private String requiredPrincipal(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "TERMINATION_DELIVERY_" + field + "_REQUIRED");
        }
        return value;
    }
}

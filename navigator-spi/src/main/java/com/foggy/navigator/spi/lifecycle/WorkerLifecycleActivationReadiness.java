package com.foggy.navigator.spi.lifecycle;

import java.util.List;
import java.util.Set;

/**
 * Server-observed, content-free readiness for activation control.  No caller
 * request may construct this as activation authority; the control plane obtains
 * it from an authenticated {@link WorkerLifecyclePort}.
 */
public record WorkerLifecycleActivationReadiness(
        boolean workerReady,
        boolean lifecycleReady,
        String lifecycleSchema,
        int lifecycleProtocol,
        WorkerLifecycleIdentity identity,
        Set<String> capabilities,
        String workerVersion,
        boolean terminationReady,
        boolean lifecycleCredentialAuthenticated,
        boolean providerCredentialConfigured,
        List<String> reasonCodes) {
    public WorkerLifecycleActivationReadiness {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}

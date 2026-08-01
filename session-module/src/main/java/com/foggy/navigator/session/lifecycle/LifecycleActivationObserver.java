package com.foggy.navigator.session.lifecycle;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LifecycleActivationObserver {
    private final LifecycleActivationProperties properties;
    private final LifecycleActivationAuthorityService authority;

    public LifecycleActivationObserver(
            LifecycleActivationProperties properties,
            LifecycleActivationAuthorityService authority) {
        this.properties = properties;
        this.authority = authority;
    }

    @Scheduled(fixedDelayString =
            "${navigator.lifecycle.activation.observer-delay:PT5S}")
    public void observe() {
        if (!properties.isControlEnabled()
                || properties.getExactTargetId() == null) {
            return;
        }
        var readiness = authority.inspect();
        if (!SetOfRenewable.STATUSES.contains(readiness.targetStatus())) {
            return;
        }
        try {
            authority.observeAndRenewConfiguredProof();
        } catch (RuntimeException ignored) {
            // The authority service persisted the stable reason and initiated
            // quarantine. Scheduler logs must not expose transport details.
        }
    }

    private static final class SetOfRenewable {
        private static final java.util.Set<String> STATUSES = java.util.Set.of(
                "READY", "RESERVED", "ADMITTED", "CONSUMED");
    }
}

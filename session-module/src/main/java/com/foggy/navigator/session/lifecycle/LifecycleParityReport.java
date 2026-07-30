package com.foggy.navigator.session.lifecycle;

import java.util.List;

public record LifecycleParityReport(
        String providerType,
        String tenantId,
        String physicalWorkerId,
        boolean exactCanaryTuple,
        int ownerEffectCount,
        List<String> blockers,
        List<String> unexplainedDifferences
) {
    public LifecycleParityReport {
        blockers = List.copyOf(blockers);
        unexplainedDifferences = List.copyOf(unexplainedDifferences);
    }

    public boolean passes() {
        return ownerEffectCount == 0
                && blockers.isEmpty()
                && unexplainedDifferences.isEmpty();
    }
}

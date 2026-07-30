package com.foggy.navigator.session.lifecycle;

import java.util.ArrayList;
import java.util.List;

public final class LifecycleShadowParityService {

    public LifecycleParityReport compare(
            String providerType,
            String tenantId,
            String physicalWorkerId,
            CanaryTuple canary,
            String legacyStatus,
            String proposedStatus,
            boolean lifecycleContextComplete,
            boolean lifecycleDurabilityReady,
            int ownerEffectCount) {
        List<String> blockers = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        if (!lifecycleContextComplete) blockers.add("LIFECYCLE_CONTEXT_GAP");
        if (!lifecycleDurabilityReady) blockers.add("LIFECYCLE_DURABILITY_UNAVAILABLE");
        if (!legacyStatus.equals(proposedStatus)) {
            differences.add("STATUS_MAPPING_DIFF");
        }
        if (ownerEffectCount != 0) blockers.add("SHADOW_OWNER_EFFECT_OBSERVED");
        return new LifecycleParityReport(
                providerType, tenantId, physicalWorkerId,
                canary.matches(providerType, tenantId, physicalWorkerId),
                ownerEffectCount, blockers, differences);
    }

    public record CanaryTuple(
            String providerType, String tenantId, String physicalWorkerId) {
        public boolean matches(String provider, String tenant, String worker) {
            return providerType.equals(provider)
                    && tenantId.equals(tenant)
                    && physicalWorkerId.equals(worker);
        }
    }
}

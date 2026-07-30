package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;

import java.util.List;

public record SentinelReconcileResult(
        SentinelReconcileState state,
        WorkerLifecycleIdentity identity,
        long throughSequence,
        List<NormalizedLifecycleFact> facts,
        boolean canonicalMutationSuppressed,
        String safeReasonCode
) {
    public SentinelReconcileResult {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static SentinelReconcileResult blocked(
            SentinelReconcileState state, String reason) {
        return new SentinelReconcileResult(state, null, 0, List.of(), true, reason);
    }
}

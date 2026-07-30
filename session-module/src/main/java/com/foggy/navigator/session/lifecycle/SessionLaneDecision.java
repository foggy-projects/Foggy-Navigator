package com.foggy.navigator.session.lifecycle;

public record SessionLaneDecision(
        boolean admitted,
        String foregroundTaskId,
        String safeReasonCode,
        boolean ownerEffectSuppressed
) {
}

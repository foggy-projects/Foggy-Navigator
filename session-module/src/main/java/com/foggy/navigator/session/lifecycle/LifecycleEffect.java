package com.foggy.navigator.session.lifecycle;

public record LifecycleEffect(
        String effectType,
        String aggregateId,
        boolean executionSuppressed,
        String idempotencyKey
) {
}

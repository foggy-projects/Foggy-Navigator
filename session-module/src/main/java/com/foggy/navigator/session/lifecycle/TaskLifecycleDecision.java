package com.foggy.navigator.session.lifecycle;

import java.util.List;

public record TaskLifecycleDecision(
        TaskLifecycleSnapshot snapshot,
        List<LifecycleEffect> requiredEffects,
        List<String> invariantViolations
) {
    public TaskLifecycleDecision {
        requiredEffects = List.copyOf(requiredEffects);
        invariantViolations = List.copyOf(invariantViolations);
    }
}

package com.foggy.navigator.session.lifecycle;

public final class OfflineCommandGate {

    public OfflineGateDecision evaluate(
            LifecycleOperationalState state,
            boolean commandSent,
            boolean lifecycleContextAttached) {
        if (!lifecycleContextAttached) {
            return new OfflineGateDecision(
                    false, true, "LIFECYCLE_CONTEXT_GAP", true);
        }
        if (state.availability() != LifecycleAvailability.READY) {
            return commandSent
                    ? new OfflineGateDecision(
                            false, true, "POST_SEND_RESULT_AMBIGUOUS", true)
                    : new OfflineGateDecision(
                            false, false, "WORKER_DEPENDENT_MUTATION_NOT_READY", true);
        }
        return new OfflineGateDecision(true, commandSent, "SHADOW_PREFLIGHT_READY", true);
    }

    public record OfflineGateDecision(
            boolean wouldAdmit,
            boolean ambiguous,
            String safeReasonCode,
            boolean ownerEffectSuppressed) {
    }
}

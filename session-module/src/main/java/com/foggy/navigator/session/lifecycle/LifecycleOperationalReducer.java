package com.foggy.navigator.session.lifecycle;

import java.util.EnumSet;
import java.util.Set;

public final class LifecycleOperationalReducer {

    private LifecycleOperationalReducer() {
    }

    public static LifecycleOperationalState reduce(Set<LifecycleBlocker> input) {
        Set<LifecycleBlocker> blockers = Set.copyOf(input);
        LifecycleConflictState conflict = conflict(blockers);
        LifecycleAvailability availability = conflict == LifecycleConflictState.NONE
                ? availability(blockers)
                : LifecycleAvailability.AUTHORITY_QUARANTINED;
        return new LifecycleOperationalState(availability, conflict, blockers);
    }

    /**
     * Merges a newly observed operational condition without implicitly
     * clearing the single persisted authority conflict. Conflict recovery is
     * a separate protocol; ordinary reconciliation can only retain or upgrade
     * according to the canonical reducer precedence.
     */
    public static LifecycleOperationalState reduceRetainingConflict(
            LifecycleConflictState retainedConflict,
            Set<LifecycleBlocker> observations) {
        EnumSet<LifecycleBlocker> blockers = observations.isEmpty()
                ? EnumSet.noneOf(LifecycleBlocker.class)
                : EnumSet.copyOf(observations);
        switch (retainedConflict) {
            case LEGACY_WRITER_EXCLUSIVITY_LOST ->
                    blockers.add(LifecycleBlocker.WRITER_EXCLUSIVITY_LOST);
            case WORKER_STATE_LOSS ->
                    blockers.add(LifecycleBlocker.WORKER_STATE_LOSS);
            case EVIDENCE_CONFLICT ->
                    blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
            case NONE -> {
                // No authority conflict to retain.
            }
        }
        return reduce(blockers);
    }

    private static LifecycleConflictState conflict(Set<LifecycleBlocker> blockers) {
        if (blockers.contains(LifecycleBlocker.WRITER_EXCLUSIVITY_LOST)) {
            return LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST;
        }
        if (blockers.contains(LifecycleBlocker.WORKER_STATE_LOSS)) {
            return LifecycleConflictState.WORKER_STATE_LOSS;
        }
        if (blockers.contains(LifecycleBlocker.EVIDENCE_CONFLICT)) {
            return LifecycleConflictState.EVIDENCE_CONFLICT;
        }
        return LifecycleConflictState.NONE;
    }

    private static LifecycleAvailability availability(Set<LifecycleBlocker> blockers) {
        if (blockers.contains(LifecycleBlocker.STORAGE_FROZEN)) {
            return LifecycleAvailability.STORAGE_FROZEN;
        }
        if (blockers.contains(LifecycleBlocker.CONFIGURATION_UNAVAILABLE)) {
            return LifecycleAvailability.CONFIGURATION_FROZEN;
        }
        if (blockers.contains(LifecycleBlocker.WORKER_OFFLINE)) {
            return LifecycleAvailability.OFFLINE_FROZEN;
        }
        if (blockers.contains(LifecycleBlocker.WORKER_RECOVERING)) {
            return LifecycleAvailability.RECOVERING;
        }
        return LifecycleAvailability.READY;
    }
}

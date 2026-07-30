package com.foggy.navigator.session.lifecycle;

import java.util.Set;

public record LifecycleOperationalState(
        LifecycleAvailability availability,
        LifecycleConflictState conflictState,
        Set<LifecycleBlocker> activeBlockers
) {
    public LifecycleOperationalState {
        activeBlockers = Set.copyOf(activeBlockers);
        boolean legal = (conflictState == LifecycleConflictState.NONE)
                == (availability != LifecycleAvailability.AUTHORITY_QUARANTINED);
        if (!legal) {
            throw new IllegalArgumentException("LIFECYCLE_OPERATIONAL_PAIR_INVALID");
        }
    }

    public boolean isLegalPair() {
        return (conflictState == LifecycleConflictState.NONE)
                == (availability != LifecycleAvailability.AUTHORITY_QUARANTINED);
    }
}

package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;

import java.util.Map;
import java.util.Set;

public record TaskLifecycleSnapshot(
        String taskId,
        TaskCanonicalPhase canonicalPhase,
        TaskTerminalOutcome terminalOutcome,
        TaskTerminalSource terminalSource,
        TaskDispatchState dispatchState,
        TaskExecutionObservation executionObservation,
        TaskInteractionState interactionState,
        TaskTerminationState terminationState,
        LifecycleAvailability availability,
        LifecycleConflictState conflictState,
        TaskCleanupState cleanupState,
        LifecycleOwnershipMode ownershipMode,
        Map<String, TaskLifecycleFact> factsById,
        Set<LifecycleBlocker> activeBlockers,
        long factCursor,
        String policyVersion,
        long aggregateVersion
) {
    public TaskLifecycleSnapshot {
        factsById = Map.copyOf(factsById);
        activeBlockers = Set.copyOf(activeBlockers);
    }

    public static TaskLifecycleSnapshot initial(String taskId) {
        return new TaskLifecycleSnapshot(
                taskId,
                TaskCanonicalPhase.OPEN,
                null,
                null,
                TaskDispatchState.NONE,
                TaskExecutionObservation.UNKNOWN,
                TaskInteractionState.NONE,
                TaskTerminationState.NONE,
                LifecycleAvailability.READY,
                LifecycleConflictState.NONE,
                TaskCleanupState.NOT_REQUIRED,
                LifecycleOwnershipMode.SHADOW,
                Map.of(),
                Set.of(),
                0,
                "unassigned",
                0);
    }

    public boolean canonicalTerminal() {
        return canonicalPhase == TaskCanonicalPhase.TERMINAL;
    }
}

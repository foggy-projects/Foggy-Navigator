package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLifecycleReducerTest {

    private final TaskLifecycleReducer reducer = new TaskLifecycleReducer();

    @Test
    void incrementalAndFullRecomputeAreEquivalentForOutOfOrderFacts() {
        List<TaskLifecycleFact> facts = List.of(
                TaskLifecycleFact.workerAccepted("f2", 2),
                TaskLifecycleFact.commandAccepted("f1", 1),
                TaskLifecycleFact.terminationAcknowledged("f4", 4),
                TaskLifecycleFact.terminationAccepted("f3", 3));

        TaskLifecycleSnapshot incremental = TaskLifecycleSnapshot.initial("task-1");
        for (TaskLifecycleFact fact : facts) {
            incremental = reducer.applyFact(incremental, fact, "policy-v1").snapshot();
        }

        TaskLifecycleDecision recomputed = reducer.recompute(
                "task-1", facts, Set.of(), "policy-v1", 7);

        assertEquals(recomputed.snapshot(), incremental);
        assertEquals(TaskCanonicalPhase.OPEN, incremental.canonicalPhase());
        assertEquals(TaskTerminationState.ACKNOWLEDGED, incremental.terminationState());
        assertFalse(incremental.canonicalTerminal());
    }

    @Test
    void ackDisconnectTimeoutAndTextNeverCreateTerminal() {
        TaskLifecycleDecision decision = reducer.recompute(
                "task-2",
                List.of(
                        TaskLifecycleFact.commandAccepted("f1", 1),
                        TaskLifecycleFact.terminationAccepted("f2", 2),
                        TaskLifecycleFact.terminationAcknowledged("f3", 3),
                        TaskLifecycleFact.workerDisconnected("f4", 4),
                        TaskLifecycleFact.terminationDeadlineElapsed("f5", 5),
                        TaskLifecycleFact.diagnosticText("f6", 6)),
                Set.of(),
                "policy-v1",
                8);

        assertEquals(TaskCanonicalPhase.OPEN, decision.snapshot().canonicalPhase());
        assertEquals(TaskTerminationState.AMBIGUOUS, decision.snapshot().terminationState());
        assertEquals(LifecycleAvailability.OFFLINE_FROZEN, decision.snapshot().availability());
        assertFalse(decision.snapshot().canonicalTerminal());
        assertTrue(decision.requiredEffects().isEmpty());
    }

    @Test
    void blockerPrecedenceAndClearRevealProduceOneLegalPair() {
        Set<LifecycleBlocker> blockers = Set.of(
                LifecycleBlocker.WORKER_OFFLINE,
                LifecycleBlocker.STORAGE_FROZEN,
                LifecycleBlocker.CONFIGURATION_UNAVAILABLE,
                LifecycleBlocker.EVIDENCE_CONFLICT,
                LifecycleBlocker.WORKER_STATE_LOSS,
                LifecycleBlocker.WRITER_EXCLUSIVITY_LOST);

        LifecycleOperationalState all = LifecycleOperationalReducer.reduce(blockers);
        assertEquals(LifecycleAvailability.AUTHORITY_QUARANTINED, all.availability());
        assertEquals(LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST, all.conflictState());

        LifecycleOperationalState withoutWriter = LifecycleOperationalReducer.reduce(Set.of(
                LifecycleBlocker.WORKER_OFFLINE,
                LifecycleBlocker.STORAGE_FROZEN,
                LifecycleBlocker.CONFIGURATION_UNAVAILABLE,
                LifecycleBlocker.EVIDENCE_CONFLICT,
                LifecycleBlocker.WORKER_STATE_LOSS));
        assertEquals(LifecycleConflictState.WORKER_STATE_LOSS, withoutWriter.conflictState());

        LifecycleOperationalState withoutConflicts = LifecycleOperationalReducer.reduce(Set.of(
                LifecycleBlocker.WORKER_OFFLINE,
                LifecycleBlocker.STORAGE_FROZEN,
                LifecycleBlocker.CONFIGURATION_UNAVAILABLE));
        assertEquals(LifecycleAvailability.STORAGE_FROZEN, withoutConflicts.availability());
        assertEquals(LifecycleConflictState.NONE, withoutConflicts.conflictState());
        assertTrue(withoutConflicts.isLegalPair());
    }
}

package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskLifecycleReducer {

    public TaskLifecycleDecision applyFact(
            TaskLifecycleSnapshot previous,
            TaskLifecycleFact fact,
            String policyVersion) {
        Map<String, TaskLifecycleFact> facts = new HashMap<>(previous.factsById());
        facts.putIfAbsent(fact.factId(), fact);
        return reduce(previous.taskId(), facts.values(), previous.activeBlockers(),
                previous.ownershipMode(), null, policyVersion);
    }

    public TaskLifecycleDecision recompute(
            String taskId,
            List<TaskLifecycleFact> facts,
            Set<LifecycleBlocker> irreversibleBlockers,
            String policyVersion,
            long evaluationTick) {
        return reduce(taskId, facts, irreversibleBlockers, LifecycleOwnershipMode.SHADOW,
                null, policyVersion);
    }

    public TaskLifecycleDecision recompute(
            String taskId,
            List<TaskLifecycleFact> facts,
            Set<LifecycleBlocker> irreversibleBlockers,
            TaskLifecycleBinding expectedBinding,
            String policyVersion) {
        return reduce(taskId, facts, irreversibleBlockers,
                expectedBinding.ownershipMode(), expectedBinding, policyVersion);
    }

    private TaskLifecycleDecision reduce(
            String taskId,
            Iterable<TaskLifecycleFact> input,
            Set<LifecycleBlocker> initialBlockers,
            LifecycleOwnershipMode ownershipMode,
            TaskLifecycleBinding expectedBinding,
            String policyVersion) {
        Map<String, TaskLifecycleFact> factsById = new HashMap<>();
        for (TaskLifecycleFact fact : input) {
            factsById.putIfAbsent(fact.factId(), fact);
        }
        List<TaskLifecycleFact> facts = factsById.values().stream()
                .sorted(Comparator.comparingLong(TaskLifecycleFact::sourceSequence)
                        .thenComparing(TaskLifecycleFact::factId))
                .toList();
        Set<TaskLifecycleFactType> types = new HashSet<>();
        Set<LifecycleBlocker> blockers = new HashSet<>(initialBlockers);
        long cursor = 0;
        TaskTerminalOutcome observedOutcome = null;
        boolean terminalEvidenceConflict = false;
        boolean exactNeverAccepted = false;
        for (TaskLifecycleFact fact : facts) {
            types.add(fact.type());
            cursor = Math.max(cursor, fact.sourceSequence());
            if (fact.type() == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED) {
                if (!fact.exactTerminalAuthority()
                        || expectedBinding == null
                        || !expectedBinding.exactRuntimeMatch(fact.binding())) {
                    blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
                    terminalEvidenceConflict = true;
                } else if (observedOutcome != null
                        && observedOutcome != fact.terminalOutcome()) {
                    blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
                    terminalEvidenceConflict = true;
                } else {
                    observedOutcome = fact.terminalOutcome();
                }
            } else if (fact.type() == TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED) {
                exactNeverAccepted = fact.exactTerminalAuthority()
                        && expectedBinding != null
                        && expectedBinding.exactRuntimeMatch(fact.binding());
                if (!exactNeverAccepted) {
                    blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
                    terminalEvidenceConflict = true;
                }
            }
        }
        if (terminalEvidenceConflict) {
            observedOutcome = null;
        }
        updateBlockers(types, blockers);

        TaskCanonicalPhase phase = TaskCanonicalPhase.OPEN;
        TaskTerminalOutcome outcome = null;
        TaskTerminalSource source = null;
        TaskDispatchState dispatch = dispatch(types);
        TaskExecutionObservation execution = execution(types);
        if (observedOutcome != null) {
            phase = TaskCanonicalPhase.TERMINAL;
            outcome = observedOutcome;
            source = TaskTerminalSource.WORKER_EVIDENCE;
            execution = TaskExecutionObservation.STOPPED;
        } else if (exactNeverAccepted
                && !types.contains(TaskLifecycleFactType.TASK_ACCEPTED_BY_WORKER)
                && !types.contains(TaskLifecycleFactType.TASK_EXECUTION_STARTED_OBSERVED)
                && !types.contains(TaskLifecycleFactType.TASK_RUNNING_OBSERVED)) {
            phase = TaskCanonicalPhase.TERMINAL;
            outcome = TaskTerminalOutcome.FAILED;
            source = TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION;
            dispatch = TaskDispatchState.REJECTED;
            execution = TaskExecutionObservation.NOT_STARTED;
        } else if (exactNeverAccepted) {
            blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
        }

        LifecycleOperationalState operational = LifecycleOperationalReducer.reduce(blockers);
        TaskTerminationState termination = termination(types, observedOutcome != null);
        TaskCleanupState cleanup = phase == TaskCanonicalPhase.TERMINAL
                ? TaskCleanupState.PENDING : TaskCleanupState.NOT_REQUIRED;
        TaskLifecycleSnapshot snapshot = new TaskLifecycleSnapshot(
                taskId, phase, outcome, source, dispatch, execution, interaction(types),
                termination, operational.availability(), operational.conflictState(), cleanup,
                ownershipMode, factsById, blockers, cursor, policyVersion, factsById.size());
        List<LifecycleEffect> effects = new ArrayList<>();
        if (phase == TaskCanonicalPhase.TERMINAL
                && !blockers.contains(LifecycleBlocker.EVIDENCE_CONFLICT)) {
            effects.add(new LifecycleEffect(
                    "COMMIT_TERMINAL_SAFETY_FENCE",
                    taskId,
                    ownershipMode != LifecycleOwnershipMode.ENFORCED,
                    "terminal-fence:" + taskId));
        }
        return new TaskLifecycleDecision(snapshot, effects, validate(snapshot));
    }

    private void updateBlockers(
            Set<TaskLifecycleFactType> types,
            Set<LifecycleBlocker> blockers) {
        set(types, blockers, TaskLifecycleFactType.WORKER_DISCONNECTED_OBSERVED,
                TaskLifecycleFactType.WORKER_RECONNECTED_OBSERVED, LifecycleBlocker.WORKER_OFFLINE);
        set(types, blockers, TaskLifecycleFactType.WORKER_RECONNECTED_OBSERVED,
                TaskLifecycleFactType.WORKER_RECONCILIATION_COMPLETED, LifecycleBlocker.WORKER_RECOVERING);
        set(types, blockers, TaskLifecycleFactType.WORKER_LIFECYCLE_STORAGE_FROZEN,
                TaskLifecycleFactType.WORKER_LIFECYCLE_STORAGE_RECOVERED, LifecycleBlocker.STORAGE_FROZEN);
        set(types, blockers, TaskLifecycleFactType.LIFECYCLE_REQUIRED_CONFIGURATION_UNAVAILABLE,
                TaskLifecycleFactType.LIFECYCLE_REQUIRED_CONFIGURATION_RESTORED,
                LifecycleBlocker.CONFIGURATION_UNAVAILABLE);
        set(types, blockers, TaskLifecycleFactType.TASK_EXECUTION_EVIDENCE_CONFLICT,
                TaskLifecycleFactType.TASK_EXECUTION_EVIDENCE_CONFLICT_RESOLVED,
                LifecycleBlocker.EVIDENCE_CONFLICT);
        set(types, blockers, TaskLifecycleFactType.WORKER_STATE_GENERATION_CHANGED,
                TaskLifecycleFactType.WORKER_STATE_BASELINE_REESTABLISHED,
                LifecycleBlocker.WORKER_STATE_LOSS);
        set(types, blockers, TaskLifecycleFactType.WRITER_EXCLUSIVITY_PROOF_LOST,
                TaskLifecycleFactType.WRITER_EXCLUSIVITY_PROOF_RESTORED,
                LifecycleBlocker.WRITER_EXCLUSIVITY_LOST);
    }

    private void set(
            Set<TaskLifecycleFactType> types,
            Set<LifecycleBlocker> blockers,
            TaskLifecycleFactType active,
            TaskLifecycleFactType cleared,
            LifecycleBlocker blocker) {
        if (types.contains(cleared)) {
            blockers.remove(blocker);
        } else if (types.contains(active)) {
            blockers.add(blocker);
        }
    }

    private TaskDispatchState dispatch(Set<TaskLifecycleFactType> facts) {
        if (facts.contains(TaskLifecycleFactType.TASK_ACCEPTED_BY_WORKER)) {
            return TaskDispatchState.WORKER_ACCEPTED;
        }
        if (facts.contains(TaskLifecycleFactType.TASK_DISPATCHED)) {
            return TaskDispatchState.DISPATCHED;
        }
        if (facts.contains(TaskLifecycleFactType.TASK_DISPATCH_RESERVED)) {
            return TaskDispatchState.RESERVED;
        }
        return TaskDispatchState.NONE;
    }

    private TaskExecutionObservation execution(Set<TaskLifecycleFactType> facts) {
        return facts.contains(TaskLifecycleFactType.TASK_EXECUTION_STARTED_OBSERVED)
                || facts.contains(TaskLifecycleFactType.TASK_RUNNING_OBSERVED)
                ? TaskExecutionObservation.RUNNING : TaskExecutionObservation.UNKNOWN;
    }

    private TaskInteractionState interaction(Set<TaskLifecycleFactType> facts) {
        if (facts.contains(TaskLifecycleFactType.TASK_AWAITING_PERMISSION_OBSERVED)) {
            return TaskInteractionState.AWAITING_PERMISSION;
        }
        if (facts.contains(TaskLifecycleFactType.TASK_AWAITING_INPUT_OBSERVED)) {
            return TaskInteractionState.AWAITING_INPUT;
        }
        return TaskInteractionState.NONE;
    }

    private TaskTerminationState termination(
            Set<TaskLifecycleFactType> facts,
            boolean terminalObserved) {
        if (terminalObserved && (facts.contains(TaskLifecycleFactType.TERMINATION_INTENT_ACCEPTED)
                || facts.contains(TaskLifecycleFactType.TERMINATION_ACKNOWLEDGED))) {
            return TaskTerminationState.CONFIRMED;
        }
        if (facts.contains(TaskLifecycleFactType.TERMINATION_REJECTED)) {
            return TaskTerminationState.REJECTED;
        }
        if (facts.contains(TaskLifecycleFactType.TERMINATION_EVIDENCE_DEADLINE_ELAPSED)) {
            return TaskTerminationState.AMBIGUOUS;
        }
        if (facts.contains(TaskLifecycleFactType.TERMINATION_ACKNOWLEDGED)) {
            return TaskTerminationState.ACKNOWLEDGED;
        }
        if (facts.contains(TaskLifecycleFactType.TERMINATION_DISPATCHED)) {
            return TaskTerminationState.DISPATCHED;
        }
        if (facts.contains(TaskLifecycleFactType.TERMINATION_INTENT_ACCEPTED)) {
            return TaskTerminationState.REQUEST_ACCEPTED;
        }
        return TaskTerminationState.NONE;
    }

    private List<String> validate(TaskLifecycleSnapshot snapshot) {
        List<String> violations = new ArrayList<>();
        if (snapshot.canonicalPhase() == TaskCanonicalPhase.TERMINAL
                && (snapshot.terminalOutcome() == null || snapshot.terminalSource() == null)) {
            violations.add("TERMINAL_PROVENANCE_REQUIRED");
        }
        if (snapshot.conflictState() != LifecycleConflictState.NONE
                && snapshot.availability() != LifecycleAvailability.AUTHORITY_QUARANTINED) {
            violations.add("CONFLICT_REQUIRES_AUTHORITY_QUARANTINE");
        }
        return violations;
    }
}

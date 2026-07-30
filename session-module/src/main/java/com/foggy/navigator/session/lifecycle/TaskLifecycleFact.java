package com.foggy.navigator.session.lifecycle;

import java.util.Objects;

public record TaskLifecycleFact(
        String factId,
        TaskLifecycleFactType type,
        long sourceSequence,
        TaskTerminalOutcome terminalOutcome
) {
    public TaskLifecycleFact {
        if (factId == null || factId.isBlank()) {
            throw new IllegalArgumentException("LIFECYCLE_FACT_ID_REQUIRED");
        }
        Objects.requireNonNull(type, "type");
        if (sourceSequence < 0) {
            throw new IllegalArgumentException("LIFECYCLE_FACT_SEQUENCE_INVALID");
        }
        if (type != TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED
                && terminalOutcome != null) {
            throw new IllegalArgumentException("LIFECYCLE_TERMINAL_OUTCOME_NOT_APPLICABLE");
        }
    }

    public static TaskLifecycleFact commandAccepted(String id, long sequence) {
        return of(id, TaskLifecycleFactType.TASK_COMMAND_ACCEPTED, sequence);
    }

    public static TaskLifecycleFact workerAccepted(String id, long sequence) {
        return of(id, TaskLifecycleFactType.TASK_ACCEPTED_BY_WORKER, sequence);
    }

    public static TaskLifecycleFact terminationAccepted(String id, long sequence) {
        return of(id, TaskLifecycleFactType.TERMINATION_INTENT_ACCEPTED, sequence);
    }

    public static TaskLifecycleFact terminationAcknowledged(String id, long sequence) {
        return of(id, TaskLifecycleFactType.TERMINATION_ACKNOWLEDGED, sequence);
    }

    public static TaskLifecycleFact terminationDeadlineElapsed(String id, long sequence) {
        return of(id, TaskLifecycleFactType.TERMINATION_EVIDENCE_DEADLINE_ELAPSED, sequence);
    }

    public static TaskLifecycleFact workerDisconnected(String id, long sequence) {
        return of(id, TaskLifecycleFactType.WORKER_DISCONNECTED_OBSERVED, sequence);
    }

    public static TaskLifecycleFact diagnosticText(String id, long sequence) {
        return of(id, TaskLifecycleFactType.DIAGNOSTIC_TEXT, sequence);
    }

    public static TaskLifecycleFact terminal(
            String id, long sequence, TaskTerminalOutcome outcome) {
        return new TaskLifecycleFact(
                id, TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED, sequence,
                Objects.requireNonNull(outcome, "outcome"));
    }

    public static TaskLifecycleFact of(String id, TaskLifecycleFactType type, long sequence) {
        return new TaskLifecycleFact(id, type, sequence, null);
    }
}

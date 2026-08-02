package com.foggy.navigator.session.lifecycle;

import java.util.Objects;

public record TaskLifecycleFact(
        String factId,
        TaskLifecycleFactType type,
        long sourceSequence,
        TaskTerminalOutcome terminalOutcome,
        TaskLifecycleBinding binding,
        boolean exactTerminalAuthority
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
        if (exactTerminalAuthority
                && (type != TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED
                && type != TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED
                && type != TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED)) {
            throw new IllegalArgumentException("LIFECYCLE_TERMINAL_AUTHORITY_NOT_APPLICABLE");
        }
        if (exactTerminalAuthority && binding == null) {
            throw new IllegalArgumentException("LIFECYCLE_TERMINAL_AUTHORITY_BINDING_REQUIRED");
        }
        if (exactTerminalAuthority
                && type == TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED
                && binding.providerTaskId() == null) {
            throw new IllegalArgumentException(
                    "LIFECYCLE_PROVIDER_TERMINAL_PROVIDER_TASK_ID_REQUIRED");
        }
        if (exactTerminalAuthority
                && type == TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED
                && binding.providerTaskId() != null) {
            throw new IllegalArgumentException(
                    "LIFECYCLE_SERVER_PRE_EFFECT_PROVIDER_TASK_ID_FORBIDDEN");
        }
    }

    public TaskLifecycleFact(
            String factId,
            TaskLifecycleFactType type,
            long sourceSequence,
            TaskTerminalOutcome terminalOutcome) {
        this(factId, type, sourceSequence, terminalOutcome, null, false);
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
                Objects.requireNonNull(outcome, "outcome"), null, false);
    }

    public static TaskLifecycleFact workerTerminal(
            String id,
            long sequence,
            TaskTerminalOutcome outcome,
            TaskLifecycleBinding binding) {
        return new TaskLifecycleFact(
                id,
                TaskLifecycleFactType.TASK_PROVIDER_TERMINAL_OBSERVED,
                sequence,
                Objects.requireNonNull(outcome, "outcome"),
                Objects.requireNonNull(binding, "binding"),
                true);
    }

    public static TaskLifecycleFact exactPreEffectRejection(
            String id, long sequence, TaskLifecycleBinding binding) {
        return new TaskLifecycleFact(
                id,
                TaskLifecycleFactType.TASK_NEVER_ACCEPTED_CONFIRMED,
                sequence,
                null,
                Objects.requireNonNull(binding, "binding"),
                true);
    }

    /**
     * A Navigator-owned admission fence rejected the task before any provider
     * call. This is deliberately distinct from a Worker never-accepted
     * observation: no Worker/provider identity is claimed beyond the reserved
     * server binding and the provider task id must remain null.
     */
    public static TaskLifecycleFact serverPreEffectAdmissionRejection(
            String id, long sequence, TaskLifecycleBinding binding) {
        return new TaskLifecycleFact(
                id,
                TaskLifecycleFactType.SERVER_PRE_EFFECT_ADMISSION_REJECTED,
                sequence,
                null,
                Objects.requireNonNull(binding, "binding"),
                true);
    }

    public static TaskLifecycleFact of(String id, TaskLifecycleFactType type, long sequence) {
        return new TaskLifecycleFact(id, type, sequence, null, null, false);
    }
}

package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import org.springframework.stereotype.Component;

@Component
public class CompatibilityTaskProjectionCleanupAction
        implements TerminalCleanupAction {
    private final SessionTaskRepository tasks;
    private final TaskTerminalTombstoneRepository tombstones;

    public CompatibilityTaskProjectionCleanupAction(
            SessionTaskRepository tasks,
            TaskTerminalTombstoneRepository tombstones) {
        this.tasks = tasks;
        this.tombstones = tombstones;
    }

    @Override
    public TerminalCleanupParticipant participant() {
        return TerminalCleanupParticipant.COMPATIBILITY_TASK_PROJECTION;
    }

    @Override
    public String execute(String taskId, String idempotencyKey) {
        var tombstone = tombstones.findById(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINAL_TOMBSTONE_REQUIRED"));
        var task = tasks.findByTaskIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "CANONICAL_TASK_REQUIRED"));
        task.setStatus(switch (tombstone.getTerminalOutcome()) {
            case "SUCCEEDED", "COMPLETED" -> "COMPLETED";
            case "CANCELLED" -> "ABORTED";
            default -> "FAILED";
        });
        tasks.save(task);
        return idempotencyKey;
    }
}

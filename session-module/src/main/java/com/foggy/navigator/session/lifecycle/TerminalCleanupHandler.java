package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import org.springframework.stereotype.Service;

@Service
public class TerminalCleanupHandler {
    private final TaskTerminalCleanupPlanRepository plans;
    private final TerminalCleanupStepExecutor steps;

    public TerminalCleanupHandler(
            TaskTerminalCleanupPlanRepository plans,
            TerminalCleanupStepExecutor steps) {
        this.plans = plans;
        this.steps = steps;
    }

    public CleanupRunResult resume(String taskId) {
        int completed = 0;
        for (var plan : plans.findByIdTaskIdOrderByIdParticipant(taskId)) {
            if ("REQUIRED".equals(plan.getApplicability())
                    && !"COMPLETED".equals(plan.getCheckpointState())) {
                try {
                    if (steps.execute(
                            taskId,
                            TerminalCleanupParticipant.valueOf(
                                    plan.getId().getParticipant()))) {
                        completed++;
                    }
                } catch (RuntimeException retryable) {
                    return new CleanupRunResult(false, completed, "CLEANUP_RETRY_REQUIRED");
                }
            }
        }
        return new CleanupRunResult(true, completed, "CLEANUP_COMPLETE");
    }

    public record CleanupRunResult(
            boolean complete, int completedSteps, String safeReasonCode) {
    }
}

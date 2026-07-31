package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalCleanupHandler {
    private final TaskTerminalCleanupPlanRepository plans;
    private final TerminalCleanupStepExecutor steps;
    private final TerminalCleanupFinalizer finalizer;

    public TerminalCleanupHandler(
            TaskTerminalCleanupPlanRepository plans,
            TerminalCleanupStepExecutor steps,
            TerminalCleanupFinalizer finalizer) {
        this.plans = plans;
        this.steps = steps;
        this.finalizer = finalizer;
    }

    /*
     * The terminal owner invokes this from afterCommit. Suspend the already
     * completed transaction so repository reads cannot reuse its stale
     * persistence context while individual checkpoints commit in REQUIRES_NEW
     * transactions.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        if (!steps.allRequiredCompleted(taskId)) {
            return new CleanupRunResult(
                    false, completed, "CLEANUP_RETRY_REQUIRED");
        }
        finalizer.complete(taskId);
        return new CleanupRunResult(true, completed, "CLEANUP_COMPLETE");
    }

    @Scheduled(fixedDelayString =
            "${navigator.lifecycle.cleanup.fixed-delay-ms:5000}")
    public void resumePending() {
        for (String taskId : plans.findTaskIdsRequiringResume()) {
            resume(taskId);
        }
    }

    public record CleanupRunResult(
            boolean complete, int completedSteps, String safeReasonCode) {
    }
}

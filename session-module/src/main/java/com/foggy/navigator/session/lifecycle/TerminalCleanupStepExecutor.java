package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TerminalCleanupStepExecutor {
    private final TaskTerminalCleanupPlanRepository plans;
    private final List<TerminalCleanupAction> actions;

    public TerminalCleanupStepExecutor(
            TaskTerminalCleanupPlanRepository plans,
            List<TerminalCleanupAction> actions) {
        this.plans = plans;
        this.actions = List.copyOf(actions);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(String taskId, TerminalCleanupParticipant participant) {
        TaskTerminalCleanupPlanEntity plan = plans.findById(
                new TaskTerminalCleanupPlanId(taskId, participant.name())).orElse(null);
        if (plan == null
                || "COMPLETED".equals(plan.getCheckpointState())
                || !"REQUIRED".equals(plan.getApplicability())) {
            return false;
        }
        TerminalCleanupAction action = actions.stream()
                .filter(candidate -> candidate.participant() == participant)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINAL_CLEANUP_ACTION_MISSING_" + participant.name()));
        String checkpoint = action.execute(
                taskId, "terminal-cleanup:" + taskId + ":" + participant.name());
        plan.setCheckpointFactId(checkpoint);
        plan.setCheckpointState("COMPLETED");
        plans.save(plan);
        return true;
    }
}

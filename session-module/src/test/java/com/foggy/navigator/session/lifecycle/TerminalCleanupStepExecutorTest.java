package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalCleanupStepExecutorTest {

    @Test
    void restartReplayDoesNotRepeatCompletedSideEffect() {
        TaskTerminalCleanupPlanRepository plans =
                mock(TaskTerminalCleanupPlanRepository.class);
        TaskTerminalCleanupPlanEntity plan = new TaskTerminalCleanupPlanEntity();
        TaskTerminalCleanupPlanId id = new TaskTerminalCleanupPlanId(
                "task-fixture", TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE.name());
        plan.setId(id);
        plan.setApplicability("REQUIRED");
        plan.setCheckpointState("PENDING");
        when(plans.findById(id)).thenReturn(Optional.of(plan));
        AtomicInteger effects = new AtomicInteger();
        TerminalCleanupAction action = new TerminalCleanupAction() {
            public TerminalCleanupParticipant participant() {
                return TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE;
            }
            public String execute(String taskId, String key) {
                effects.incrementAndGet();
                return "checkpoint-fixture";
            }
        };
        TerminalCleanupStepExecutor executor =
                new TerminalCleanupStepExecutor(plans, List.of(action));
        assertThat(executor.execute(
                "task-fixture", TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE)).isTrue();
        assertThat(executor.execute(
                "task-fixture", TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE)).isFalse();
        assertThat(effects).hasValue(1);
    }
}

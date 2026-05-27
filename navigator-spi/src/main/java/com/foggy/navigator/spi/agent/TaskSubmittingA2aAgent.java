package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aTask;

/**
 * A2Agent extension for application-level task submission.
 * <p>
 * Implementations should usually be pipeline/decorator layers. Provider
 * adapters should keep {@link A2aAgent#sendTask} as the final runtime call.
 */
public interface TaskSubmittingA2aAgent extends A2aAgent {

    /** Submit a complex Agent dispatch task through the task orchestration pipeline. */
    A2aTask submitTask(AgentTaskSubmitRequest request);
}

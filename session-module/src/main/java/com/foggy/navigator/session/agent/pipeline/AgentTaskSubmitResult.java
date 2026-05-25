package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;

public class AgentTaskSubmitResult {

    private final A2aTask task;
    private final DispatchTaskDTO dispatchTask;

    private AgentTaskSubmitResult(A2aTask task, DispatchTaskDTO dispatchTask) {
        this.task = task;
        this.dispatchTask = dispatchTask;
    }

    public static AgentTaskSubmitResult of(A2aTask task) {
        return new AgentTaskSubmitResult(task, null);
    }

    public static AgentTaskSubmitResult of(A2aTask task, DispatchTaskDTO dispatchTask) {
        return new AgentTaskSubmitResult(task, dispatchTask);
    }

    public A2aTask getTask() {
        return task;
    }

    public DispatchTaskDTO getDispatchTask() {
        return dispatchTask;
    }
}

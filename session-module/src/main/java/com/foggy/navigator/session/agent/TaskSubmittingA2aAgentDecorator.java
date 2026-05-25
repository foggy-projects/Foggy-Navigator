package com.foggy.navigator.session.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.TaskSubmittingA2aAgent;

import java.util.Optional;

/**
 * A2Agent decorator that intercepts complex task submission.
 * <p>
 * Provider adapters still own only the runtime {@link #sendTask(A2aMessage)}
 * call. Application-level task lifecycle is routed through the submit pipeline.
 */
public class TaskSubmittingA2aAgentDecorator implements TaskSubmittingA2aAgent {

    private final A2aAgent delegate;
    private final AgentSubmitPipeline submitPipeline;
    private final String defaultAgentId;
    private final AgentResolveContext defaultResolveContext;

    public TaskSubmittingA2aAgentDecorator(A2aAgent delegate,
                                           AgentSubmitPipeline submitPipeline,
                                           String defaultAgentId,
                                           AgentResolveContext defaultResolveContext) {
        this.delegate = delegate;
        this.submitPipeline = submitPipeline;
        this.defaultAgentId = defaultAgentId;
        this.defaultResolveContext = defaultResolveContext;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return delegate.getAgentCard();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        return delegate.sendTask(message);
    }

    @Override
    public A2aTask submitTask(AgentTaskSubmitRequest request) {
        AgentTaskSubmitRequest effective = request != null ? request : new AgentTaskSubmitRequest();
        if (!hasText(effective.getAgentId())) {
            effective.setAgentId(defaultAgentId);
        }
        if (effective.getResolveContext() == null) {
            effective.setResolveContext(defaultResolveContext);
        }
        return submitPipeline.submit(effective).getTask();
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        return delegate.getTask(taskId);
    }

    @Override
    public void cancelTask(String taskId) {
        delegate.cancelTask(taskId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

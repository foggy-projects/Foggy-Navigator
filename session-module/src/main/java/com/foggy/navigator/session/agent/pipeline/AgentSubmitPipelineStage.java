package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;

/**
 * Decorates or handles complex Agent task submission.
 */
public interface AgentSubmitPipelineStage {

    String name();

    int order();

    default boolean supports(AgentTaskSubmitRequest request) {
        return true;
    }

    AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain);
}

package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;

/**
 * Pipeline entry for application-level Agent task submission.
 */
public interface AgentSubmitPipeline {

    AgentTaskSubmitResult submit(AgentTaskSubmitRequest request);
}

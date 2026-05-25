package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;

public interface AgentSubmitPipelineChain {

    AgentTaskSubmitResult proceed(AgentTaskSubmitRequest request);
}

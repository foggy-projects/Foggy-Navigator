package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentSubmitTracePipelineStage implements AgentSubmitPipelineStage {

    @Override
    public String name() {
        return "agent-submit-trace";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
        log.info("Agent submit pipeline enter: source={}, agentId={}, modelConfigId={}, workerId={}, directoryId={}, contextId={}",
                request.getResolveContext() != null ? request.getResolveContext().getRequestSource() : null,
                request.getAgentId(), request.getModelConfigId(), request.getWorkerId(),
                request.getDirectoryId(), request.getContextId());
        AgentTaskSubmitResult result = chain.proceed(request);
        log.info("Agent submit pipeline exit: agentId={}, taskId={}, contextId={}, state={}",
                request.getAgentId(),
                result != null && result.getTask() != null ? result.getTask().getId() : null,
                result != null && result.getTask() != null ? result.getTask().getContextId() : null,
                result != null && result.getTask() != null && result.getTask().getStatus() != null
                        ? result.getTask().getStatus().getState()
                        : null);
        return result;
    }
}

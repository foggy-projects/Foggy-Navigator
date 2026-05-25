package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class DefaultAgentSubmitPipeline implements AgentSubmitPipeline {

    private final List<AgentSubmitPipelineStage> stages;

    public DefaultAgentSubmitPipeline(List<AgentSubmitPipelineStage> stages) {
        this.stages = stages.stream()
                .sorted(Comparator.comparingInt(AgentSubmitPipelineStage::order))
                .toList();
        log.info("Agent submit pipeline initialized: stages={}",
                this.stages.stream().map(AgentSubmitPipelineStage::name).toList());
    }

    @Override
    public AgentTaskSubmitResult submit(AgentTaskSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("submit request is required");
        }
        return new DefaultChain(stages, 0).proceed(request);
    }

    private static final class DefaultChain implements AgentSubmitPipelineChain {

        private final List<AgentSubmitPipelineStage> stages;
        private final int index;

        private DefaultChain(List<AgentSubmitPipelineStage> stages, int index) {
            this.stages = stages;
            this.index = index;
        }

        @Override
        public AgentTaskSubmitResult proceed(AgentTaskSubmitRequest request) {
            for (int i = index; i < stages.size(); i++) {
                AgentSubmitPipelineStage stage = stages.get(i);
                if (!stage.supports(request)) {
                    continue;
                }
                return stage.handle(request, new DefaultChain(stages, i + 1));
            }
            throw new IllegalStateException("No Agent submit pipeline stage handled request: agentId="
                    + (request != null ? request.getAgentId() : null));
        }
    }
}

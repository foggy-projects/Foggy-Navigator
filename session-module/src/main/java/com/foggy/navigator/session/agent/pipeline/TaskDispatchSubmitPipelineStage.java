package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskDispatchSubmitPipelineStage implements AgentSubmitPipelineStage {

    private final TaskDispatchFacade taskDispatchFacade;

    @Override
    public String name() {
        return "task-dispatch-submit";
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
        DispatchTaskDTO dto = taskDispatchFacade.submitTaskDispatch(request);
        return AgentTaskSubmitResult.of(taskDispatchFacade.toA2aTask(dto), dto);
    }
}

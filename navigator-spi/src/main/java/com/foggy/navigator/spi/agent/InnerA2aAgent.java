package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aTask;

import java.util.Optional;

/**
 * 内层 Agent 接口 — 接收已解析的上下文，执行实际任务。
 * 外层 ContextResolvingA2aAgent 负责上下文解析后委托给此接口。
 */
public interface InnerA2aAgent {
    A2aAgentCard getAgentCard();
    A2aTask sendTask(A2aContext context);
    Optional<A2aTask> getTask(String taskId);
    void cancelTask(String taskId);
}

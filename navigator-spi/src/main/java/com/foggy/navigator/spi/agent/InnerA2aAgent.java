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

    /**
     * 检查指定的 agent session 是否有正在运行的任务。
     * ContextResolvingA2aAgent 在多轮续接时调用，防止向正在执行的 session 发送新任务。
     *
     * @param agentSessionRef agent 侧的会话标识（claudeSessionId / codexThreadId）
     * @return true 表示有 RUNNING 任务，应拒绝新请求
     */
    default boolean isSessionBusy(String agentSessionRef) {
        return false;
    }
}

package com.foggy.navigator.agent.framework.core;

import com.foggy.navigator.agent.framework.session.Message;

/**
 * Agent调用器接口
 * session-module 通过此接口触发 Agent 执行，不直接依赖 Agent 实现
 */
public interface AgentInvoker {

    /**
     * 异步调用Agent处理消息
     *
     * @param sessionId   会话ID
     * @param agentId     目标Agent ID
     * @param userMessage 用户消息（已持久化）
     */
    void invokeAsync(String sessionId, String agentId, Message userMessage);
}

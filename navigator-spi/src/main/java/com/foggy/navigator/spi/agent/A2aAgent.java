package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;

import java.util.Optional;

/**
 * 统一 Agent 执行接口，对齐 A2A 协议核心操作
 */
public interface A2aAgent {

    /** Agent identity card */
    A2aAgentCard getAgentCard();

    /** Send a task — returns Task in current state (WORKING or COMPLETED) */
    A2aTask sendTask(A2aMessage message);

    /** Query task status */
    Optional<A2aTask> getTask(String taskId);

    /** Cancel a running task */
    void cancelTask(String taskId);
}

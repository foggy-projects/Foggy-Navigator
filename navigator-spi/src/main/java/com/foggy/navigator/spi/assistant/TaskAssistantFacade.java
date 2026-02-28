package com.foggy.navigator.spi.assistant;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;

import java.util.Optional;

/**
 * 任务助手门面接口 (SPI)
 * 初始实现基于 Claude Worker，后续可切换为任何 A2A Server
 */
public interface TaskAssistantFacade {

    /**
     * 发送一批平台事件给助手，返回 LLM 生成的通知消息
     */
    Optional<A2aMessage> sendEvents(String userId, A2aMessage events);

    /**
     * 获取 Agent Card (A2A spec)
     */
    A2aAgentCard getAgentCard();

    /**
     * 检查助手是否对该用户可用
     */
    boolean isAvailable(String userId);

    /**
     * 获取用户配置
     */
    Optional<TaskAssistantConfig> getConfig(String userId);

    /**
     * 配置助手（选择 worker + directory）
     */
    TaskAssistantConfig configure(String userId, String workerId, String directoryId);

    /**
     * 启用/禁用
     */
    void setEnabled(String userId, boolean enabled);
}

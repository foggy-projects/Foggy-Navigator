package com.foggy.navigator.task.assistant.spi;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务助手门面接口
 * 基于 Claude Code Worker 的 AI 编程会话管理助手
 */
public interface TaskAssistantFacade {

    /**
     * 处理一批平台事件 — 创建 AgentTask 记录、调用 syncQuery、完成任务、推送通知
     */
    void processEvents(String userId, List<Map<String, Object>> events);

    /**
     * @deprecated 使用 {@link #processEvents(String, List)} 替代
     */
    @Deprecated
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
     * 创建或更新助手配置，绑定 Worker + 目录 + 模型
     * @param modelConfigId 平台 LLM 配置 ID（绑定到工作目录 auth）
     * @param model         模型名称（传给 Worker 的 --model 参数）
     */
    TaskAssistantConfig createOrUpdate(String userId, String workerId, String directoryPath,
                                        String modelConfigId, String model);

    /**
     * 启用/禁用
     */
    void setEnabled(String userId, boolean enabled);

    /**
     * 删除助手配置
     */
    void delete(String userId);
}

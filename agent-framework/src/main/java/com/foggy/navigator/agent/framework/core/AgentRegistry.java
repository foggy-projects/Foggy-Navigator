package com.foggy.navigator.agent.framework.core;

import com.foggy.navigator.agent.framework.core.model.AgentConfig;

import java.util.List;

/**
 * Agent注册表
 * 管理所有已注册的Agent
 */
public interface AgentRegistry {

    /**
     * 注册Agent
     */
    void register(AgentConfig config);

    /**
     * 注销Agent
     */
    void unregister(String agentId);

    /**
     * 根据ID查找Agent
     */
    AgentInfo findById(String agentId);

    /**
     * 根据能力查找Agent
     */
    List<AgentInfo> findByCapability(String capability);

    /**
     * 获取所有已注册的Agent
     */
    List<AgentInfo> findAll();

    /**
     * 检查Agent是否已注册
     */
    boolean exists(String agentId);

    /**
     * 更新Agent状态
     */
    void updateStatus(String agentId, AgentStatus status);
}

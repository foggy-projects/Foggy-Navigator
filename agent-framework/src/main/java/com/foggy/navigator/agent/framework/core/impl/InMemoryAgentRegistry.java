package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存实现的Agent注册表
 * MVP阶段使用，后续可替换为Redis/DB实现
 */
@Component
public class InMemoryAgentRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentConfig config) {
        AgentInfo info = AgentInfo.fromConfig(config);
        agents.put(config.getId(), info);
    }

    @Override
    public void unregister(String agentId) {
        agents.remove(agentId);
    }

    @Override
    public AgentInfo findById(String agentId) {
        return agents.get(agentId);
    }

    @Override
    public List<AgentInfo> findByCapability(String capability) {
        return agents.values().stream()
                .filter(info -> info.getCapabilities() != null
                        && info.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentInfo> findAll() {
        return List.copyOf(agents.values());
    }

    @Override
    public boolean exists(String agentId) {
        return agents.containsKey(agentId);
    }

    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        AgentInfo info = agents.get(agentId);
        if (info != null) {
            info.setStatus(status);
            info.setLastActiveAt(LocalDateTime.now());
        }
    }
}

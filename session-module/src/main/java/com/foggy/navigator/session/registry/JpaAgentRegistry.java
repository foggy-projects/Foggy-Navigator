package com.foggy.navigator.session.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import com.foggy.navigator.common.entity.AgentConfigEntity;
import com.foggy.navigator.session.repository.AgentConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JPA 持久化的 Agent 注册表
 * 内部维护 ConcurrentHashMap 缓存 + JPA 持久化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaAgentRegistry implements AgentRegistry {

    private final AgentConfigRepository agentConfigRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AgentInfo> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Loading agent configurations from database...");
        List<AgentConfigEntity> entities = agentConfigRepository.findByStatus("ACTIVE");
        for (AgentConfigEntity entity : entities) {
            try {
                AgentConfig config = objectMapper.readValue(entity.getConfigJson(), AgentConfig.class);
                AgentInfo info = AgentInfo.fromConfig(config);
                cache.put(entity.getId(), info);
                log.info("Loaded agent config from DB: id={}, name={}", entity.getId(), entity.getName());
            } catch (Exception e) {
                log.error("Failed to load agent config from DB: id={}", entity.getId(), e);
            }
        }
        log.info("Loaded {} agent configurations from database", cache.size());
    }

    @Override
    public void register(AgentConfig config) {
        // 写缓存
        AgentInfo info = AgentInfo.fromConfig(config);
        cache.put(config.getId(), info);

        // 写 DB（upsert 语义）
        try {
            String configJson = objectMapper.writeValueAsString(config);
            AgentConfigEntity entity = agentConfigRepository.findById(config.getId())
                    .orElse(AgentConfigEntity.builder()
                            .id(config.getId())
                            .registeredAt(LocalDateTime.now())
                            .build());
            entity.setName(config.getName());
            entity.setType(config.getType());
            entity.setDescription(config.getDescription());
            entity.setConfigJson(configJson);
            entity.setStatus("ACTIVE");
            agentConfigRepository.save(entity);
            log.debug("Agent config persisted: id={}", config.getId());
        } catch (Exception e) {
            log.error("Failed to persist agent config: id={}", config.getId(), e);
        }
    }

    @Override
    public void unregister(String agentId) {
        cache.remove(agentId);
        agentConfigRepository.deleteById(agentId);
    }

    @Override
    public AgentInfo findById(String agentId) {
        return cache.get(agentId);
    }

    @Override
    public List<AgentInfo> findByCapability(String capability) {
        return cache.values().stream()
                .filter(info -> info.getCapabilities() != null
                        && info.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentInfo> findAll() {
        return List.copyOf(cache.values());
    }

    @Override
    public boolean exists(String agentId) {
        return cache.containsKey(agentId);
    }

    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        AgentInfo info = cache.get(agentId);
        if (info != null) {
            info.setStatus(status);
            info.setLastActiveAt(LocalDateTime.now());
        }
    }
}

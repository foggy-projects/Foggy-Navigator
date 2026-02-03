package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillConfigLoader;
import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.spi.config.SkillConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据库 Skill 配置加载器
 * 从数据库加载 Skill 配置，并提供本地缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(SkillConfigManager.class)
public class DatabaseSkillConfigLoader implements SkillConfigLoader {

    private final SkillConfigManager skillConfigManager;

    /**
     * 缓存 Key: {agentId}:{tenantId}
     */
    private final Map<String, List<Skill>> cache = new ConcurrentHashMap<>();

    @Override
    public List<Skill> loadSkillsForAgent(String agentId, String tenantId) {
        String cacheKey = buildCacheKey(agentId, tenantId);

        // 检查缓存
        List<Skill> cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("Loading skills from cache: agentId={}, tenantId={}, count={}",
                agentId, tenantId, cached.size());
            return cached;
        }

        // 从数据库加载
        log.info("Loading skills from database: agentId={}, tenantId={}", agentId, tenantId);
        List<SkillConfigDTO> dtos = skillConfigManager.getSkillsForAgent(agentId, tenantId);
        List<Skill> skills = dtos.stream()
            .map(this::toSkill)
            .collect(Collectors.toList());

        // 更新缓存
        cache.put(cacheKey, skills);
        log.info("Loaded {} skills for agent: agentId={}", skills.size(), agentId);

        return skills;
    }

    @Override
    public Skill loadSkill(String skillId) {
        log.debug("Loading skill: id={}", skillId);
        return skillConfigManager.getSkillConfig(skillId)
            .map(this::toSkill)
            .orElse(null);
    }

    @Override
    public void refreshSkills(String agentId) {
        log.info("Refreshing skills cache for agent: {}", agentId);
        // 移除所有以该 agentId 开头的缓存
        cache.keySet().removeIf(key -> key.startsWith(agentId + ":"));
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        log.info("Clearing all skills cache");
        cache.clear();
    }

    private String buildCacheKey(String agentId, String tenantId) {
        return agentId + ":" + (tenantId != null ? tenantId : "default");
    }

    private Skill toSkill(SkillConfigDTO dto) {
        return Skill.builder()
            .id(dto.getId())
            .name(dto.getName())
            .agentId(dto.getAgentId())
            .triggerKeywords(dto.getTriggerKeywords())
            .intents(dto.getIntents())
            .description(dto.getDescription())
            .executionLogic(dto.getExecutionLogic())
            .outputFormat(dto.getOutputFormat())
            .delegationCondition(dto.getDelegationCondition())
            .markdownPath(null) // 数据库存储不使用文件路径
            .loadedAt(LocalDateTime.now())
            .build();
    }
}

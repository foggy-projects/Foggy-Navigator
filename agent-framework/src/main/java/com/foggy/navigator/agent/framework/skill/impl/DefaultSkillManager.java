package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillConfigLoader;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.skill.SkillMatcher;
import com.foggy.navigator.agent.framework.skill.SkillParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 默认Skill管理器实现
 * 支持从文件系统和数据库两种方式加载 Skill
 */
@Slf4j
@Component
public class DefaultSkillManager implements SkillManager {

    private final SkillParser skillParser;
    private final SkillMatcher skillMatcher;
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> agentSkills = new ConcurrentHashMap<>();

    /**
     * 可选的数据库 Skill 加载器
     */
    @Autowired(required = false)
    private SkillConfigLoader skillConfigLoader;

    public DefaultSkillManager(SkillParser skillParser, SkillMatcher skillMatcher) {
        this.skillParser = skillParser;
        this.skillMatcher = skillMatcher;
    }

    @Override
    public void loadSkills(String agentId, String directory) {
        Path dirPath = Path.of(directory);
        if (!Files.exists(dirPath)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(dirPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            Skill skill = skillParser.parseFile(p.toString());
                            skill.setAgentId(agentId);
                            registerSkill(skill);
                        } catch (Exception e) {
                            // Log and continue
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skills from: " + directory, e);
        }
    }

    @Override
    public void registerSkill(Skill skill) {
        if (skill.getId() == null || skill.getId().isBlank()) {
            skill.setId(java.util.UUID.randomUUID().toString());
        }
        skills.put(skill.getId(), skill);

        if (skill.getAgentId() != null) {
            agentSkills.computeIfAbsent(skill.getAgentId(), k -> new ArrayList<>())
                    .add(skill.getId());
        }
    }

    @Override
    public Skill matchSkill(String userMessage, String agentId) {
        List<Skill> availableSkills = getSkillsByAgent(agentId);
        return skillMatcher.match(userMessage, availableSkills);
    }

    @Override
    public List<Skill> getSkillsByAgent(String agentId) {
        List<String> skillIds = agentSkills.get(agentId);
        if (skillIds == null) {
            return List.of();
        }
        return skillIds.stream()
                .map(skills::get)
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    @Override
    public Skill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * 从数据库配置加载 Skill（需要 SkillConfigLoader 可用）
     * @param agentId Agent ID
     * @param tenantId 租户 ID（可选）
     */
    public void loadSkillsFromConfig(String agentId, String tenantId) {
        if (skillConfigLoader == null) {
            log.warn("SkillConfigLoader not available, skipping database skill loading");
            return;
        }

        log.info("Loading skills from config for agent: {}", agentId);
        List<Skill> configSkills = skillConfigLoader.loadSkillsForAgent(agentId, tenantId);

        for (Skill skill : configSkills) {
            skill.setAgentId(agentId);
            registerSkill(skill);
        }

        log.info("Loaded {} skills from config for agent: {}", configSkills.size(), agentId);
    }

    /**
     * 刷新 Agent 的 Skill（清除本地缓存并重新加载）
     * @param agentId Agent ID
     * @param tenantId 租户 ID（可选）
     */
    public void refreshSkills(String agentId, String tenantId) {
        // 清除本地缓存
        List<String> skillIds = agentSkills.remove(agentId);
        if (skillIds != null) {
            skillIds.forEach(skills::remove);
        }

        // 刷新数据库加载器的缓存
        if (skillConfigLoader != null) {
            skillConfigLoader.refreshSkills(agentId);
        }

        // 重新加载
        loadSkillsFromConfig(agentId, tenantId);
    }
}

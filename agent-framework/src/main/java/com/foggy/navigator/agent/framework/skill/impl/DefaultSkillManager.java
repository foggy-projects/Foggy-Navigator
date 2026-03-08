package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.skill.SkillMatcher;
import com.foggy.navigator.agent.framework.skill.SkillParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
 * 默认 Skill 管理器实现
 * 支持从文件系统和 classpath 加载 Skill
 */
@Slf4j
@Component
public class DefaultSkillManager implements SkillManager {

    private final SkillParser skillParser;
    private final SkillMatcher skillMatcher;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * agentId -> List<Skill>
     */
    private final ConcurrentHashMap<String, List<Skill>> agentSkills = new ConcurrentHashMap<>();

    public DefaultSkillManager(SkillParser skillParser, SkillMatcher skillMatcher) {
        this.skillParser = skillParser;
        this.skillMatcher = skillMatcher;
    }

    @Override
    public void loadSkills(String agentId, String directory) {
        if (directory.startsWith("classpath:")) {
            loadSkillsFromClasspath(agentId, directory);
        } else {
            loadSkillsFromFileSystem(agentId, directory);
        }
    }

    private void loadSkillsFromClasspath(String agentId, String classpathDir) {
        try {
            // 查找所有 SKILL.md 文件
            String pattern = classpathDir.endsWith("/")
                    ? classpathDir + "*/SKILL.md"
                    : classpathDir + "/*/SKILL.md";
            Resource[] resources = resourceResolver.getResources(pattern);

            log.info("Found {} skills in classpath: {}", resources.length, classpathDir);

            for (Resource resource : resources) {
                try {
                    // 从资源路径推断技能目录路径
                    String resourcePath = resource.getURL().toString();
                    String skillDir = resourcePath.substring(0, resourcePath.lastIndexOf("/SKILL.md"));

                    // 转换为 classpath: 格式（仅在 exploded classes 模式下有效）
                    int classpathIndex = skillDir.indexOf("classes/");
                    if (classpathIndex >= 0) {
                        skillDir = "classpath:" + skillDir.substring(classpathIndex + 8);
                        Skill skill = skillParser.loadFromDirectory(skillDir);
                        skill.setAgentId(agentId);
                        registerSkill(skill);
                        log.debug("Loaded skill: {} from {}", skill.getName(), skillDir);
                    } else {
                        // JAR 模式：直接读取 Resource 内容，无需转换路径
                        Skill skill = skillParser.parseResource(resource, skillDir);
                        skill.setAgentId(agentId);
                        registerSkill(skill);
                        log.debug("Loaded skill: {} from JAR: {}", skill.getName(), skillDir);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load skill from: {}", resource.getURL(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan classpath for skills: {}", classpathDir, e);
        }
    }

    private void loadSkillsFromFileSystem(String agentId, String directory) {
        Path dirPath = Path.of(directory);
        if (!Files.exists(dirPath)) {
            log.warn("Skill directory not found: {}", directory);
            return;
        }

        try (Stream<Path> paths = Files.list(dirPath)) {
            paths.filter(Files::isDirectory)
                    .forEach(skillDir -> {
                        Path skillMdPath = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillMdPath)) {
                            try {
                                Skill skill = skillParser.loadFromDirectory(skillDir.toString());
                                skill.setAgentId(agentId);
                                registerSkill(skill);
                                log.debug("Loaded skill: {} from {}", skill.getName(), skillDir);
                            } catch (Exception e) {
                                log.warn("Failed to load skill from: {}", skillDir, e);
                            }
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to load skills from: {}", directory, e);
        }
    }

    @Override
    public void registerSkill(Skill skill) {
        if (skill.getAgentId() == null) {
            log.warn("Skill {} has no agentId, skipping registration", skill.getName());
            return;
        }

        agentSkills.computeIfAbsent(skill.getAgentId(), k -> new ArrayList<>()).add(skill);
        log.info("Registered skill: {} for agent: {}", skill.getName(), skill.getAgentId());
    }

    @Override
    public Skill matchSkill(String userMessage, String agentId) {
        List<Skill> availableSkills = getSkillsByAgent(agentId);
        return skillMatcher.match(userMessage, availableSkills);
    }

    @Override
    public List<Skill> getSkillsByAgent(String agentId) {
        return agentSkills.getOrDefault(agentId, List.of());
    }

    @Override
    public Skill getSkillByName(String agentId, String skillName) {
        return getSkillsByAgent(agentId).stream()
                .filter(s -> skillName.equals(s.getName()))
                .findFirst()
                .orElse(null);
    }
}

package com.foggy.navigator.agent.skill.impl;

import com.foggy.navigator.agent.skill.Skill;
import com.foggy.navigator.agent.skill.SkillManager;
import com.foggy.navigator.agent.skill.SkillMatcher;
import com.foggy.navigator.agent.skill.SkillParser;
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
 */
@Component
public class DefaultSkillManager implements SkillManager {

    private final SkillParser skillParser;
    private final SkillMatcher skillMatcher;
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> agentSkills = new ConcurrentHashMap<>();

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
}

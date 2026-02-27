package com.foggy.navigator.claude.worker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 启动时将 classpath 中的平台技能部署到 ~/.claude/skills/，
 * 确保 Claude Code 原生 skill 机制可靠加载。
 */
@Slf4j
@Component
public class PlatformSkillDeployer {

    private static final String SKILLS_RESOURCE_PATTERN = "classpath:platform-skills/*/SKILL.md";

    @PostConstruct
    public void deploy() {
        Path claudeSkillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                deploySkill(resource, claudeSkillsDir);
            }
        } catch (IOException e) {
            log.warn("Failed to scan platform skills resources: {}", e.getMessage());
        }
    }

    private void deploySkill(Resource resource, Path claudeSkillsDir) {
        try {
            // 从资源路径提取技能名称: platform-skills/{skillName}/SKILL.md
            String path = resource.getURL().getPath();
            String[] parts = path.split("/");
            String skillName = null;
            for (int i = 0; i < parts.length - 1; i++) {
                if ("platform-skills".equals(parts[i])) {
                    skillName = parts[i + 1];
                    break;
                }
            }
            if (skillName == null) {
                log.warn("Could not extract skill name from resource: {}", path);
                return;
            }

            Path targetDir = claudeSkillsDir.resolve(skillName);
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve("SKILL.md");
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Deployed platform skill: {} -> {}", skillName, targetFile);
        } catch (IOException e) {
            log.warn("Failed to deploy platform skill {}: {}", resource.getFilename(), e.getMessage());
        }
    }
}

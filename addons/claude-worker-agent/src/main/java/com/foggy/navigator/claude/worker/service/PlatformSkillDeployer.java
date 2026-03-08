package com.foggy.navigator.claude.worker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 启动时将 classpath 中的平台技能部署到 ~/.claude/skills/，
 * 确保 Claude Code 原生 skill 机制可靠加载。
 * <p>
 * 模板中的 {@code {{NAVIGATOR_API_BASE}}} 占位符会被替换为实际的后端地址。
 */
@Slf4j
@Component
public class PlatformSkillDeployer {

    private static final String SKILLS_RESOURCE_PATTERN = "classpath:platform-skills/*/SKILL.md";

    @Value("${navigator.api.external-url:http://localhost:${server.port:8112}}")
    private String navigatorApiBase;

    @PostConstruct
    public void deploy() {
        Path claudeSkillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Map<String, String> vars = Map.of(
                "{{NAVIGATOR_API_BASE}}", navigatorApiBase
        );

        try {
            Resource[] resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                deploySkill(resource, claudeSkillsDir, vars);
            }
        } catch (IOException e) {
            log.warn("Failed to scan platform skills resources: {}", e.getMessage());
        }
    }

    private void deploySkill(Resource resource, Path claudeSkillsDir, Map<String, String> vars) {
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

            // 读取模板并替换占位符
            String content;
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (var entry : vars.entrySet()) {
                content = content.replace(entry.getKey(), entry.getValue());
            }

            Path targetDir = claudeSkillsDir.resolve(skillName);
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);

            log.info("Deployed platform skill: {} -> {} (apiBase={})", skillName, targetDir, navigatorApiBase);
        } catch (IOException e) {
            log.warn("Failed to deploy platform skill {}: {}", resource.getFilename(), e.getMessage());
        }
    }
}

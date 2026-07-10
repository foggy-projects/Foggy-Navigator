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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/**
 * 启动时将 classpath 中的平台技能部署到 ~/.agents/skills/，
 * 确保 Claude Code 原生 skill 机制可靠加载。
 * <p>
 * 模板中的 {@code {{NAVIGATOR_API_BASE}}} 占位符会被替换为实际的后端地址。
 */
@Slf4j
@Component
public class PlatformSkillDeployer {

    private static final String SKILLS_RESOURCE_PATTERN = "classpath:platform-skills/*/SKILL.md";
    private static final String RETIRED_SKILL_NAME = "cross-project-task";
    private static final List<String> RETIRED_SKILL_SIGNATURES = List.of(
            "name: cross-project-task",
            "/api/v1/cross-project-tasks"
    );

    @Value("${navigator.api.external-url:http://localhost:${server.port:8112}}")
    private String navigatorApiBase;

    @PostConstruct
    public void deploy() {
        Path userHome = Path.of(System.getProperty("user.home"));
        retireSkills(userHome);

        Path agentSkillsDir = userHome.resolve(".agents").resolve("skills");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Map<String, String> vars = Map.of(
                "{{NAVIGATOR_API_BASE}}", navigatorApiBase
        );

        try {
            Resource[] resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                deploySkill(resource, agentSkillsDir, vars);
            }
        } catch (IOException e) {
            log.warn("Failed to scan platform skills resources: {}", e.getMessage());
        }
    }

    void retireSkills(Path userHome) {
        List<Path> retiredSkillDirs = List.of(
                userHome.resolve(".agents").resolve("skills").resolve(RETIRED_SKILL_NAME),
                userHome.resolve(".agent").resolve("skills").resolve(RETIRED_SKILL_NAME),
                userHome.resolve(".claude").resolve("skills").resolve(RETIRED_SKILL_NAME)
        );
        retiredSkillDirs.forEach(this::retireSkill);
    }

    private void retireSkill(Path skillDir) {
        try {
            if (!Files.exists(skillDir, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparsePoint(skillDir)) {
                return;
            }

            Path skillFile = skillDir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparsePoint(skillFile)) {
                return;
            }

            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            if (RETIRED_SKILL_SIGNATURES.stream().anyMatch(signature -> !content.contains(signature))) {
                log.warn("Skipped unrecognized retired skill file: {}", skillFile);
                return;
            }

            Files.delete(skillFile);
            try {
                Files.delete(skillDir);
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Preserve user-managed files next to the retired SKILL.md.
            }
            log.info("Retired platform skill: {}", skillFile);
        } catch (IOException e) {
            log.warn("Failed to retire platform skill {}: {}", skillDir, e.getMessage());
        }
    }

    private boolean isLinkOrReparsePoint(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private void deploySkill(Resource resource, Path agentSkillsDir, Map<String, String> vars) {
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

            Path targetDir = agentSkillsDir.resolve(skillName);
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);

            log.info("Deployed platform skill: {} -> {} (apiBase={})", skillName, targetDir, navigatorApiBase);
        } catch (IOException e) {
            log.warn("Failed to deploy platform skill {}: {}", resource.getFilename(), e.getMessage());
        }
    }
}

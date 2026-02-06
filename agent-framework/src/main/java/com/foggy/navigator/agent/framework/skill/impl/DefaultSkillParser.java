package com.foggy.navigator.agent.framework.skill.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 默认 Skill 解析器
 * 解析 Claude Code 格式的 SKILL.md 文件
 *
 * 格式：
 * ---
 * name: skill-name
 * description: 功能描述。当用户需要...时使用。
 * ---
 *
 * # Skill Title
 * ...body content...
 */
@Slf4j
@Component
public class DefaultSkillParser implements SkillParser {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL
    );

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    @Override
    public Skill parse(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        if (!matcher.matches()) {
            log.warn("SKILL.md missing YAML frontmatter, content will be used as-is");
            return Skill.builder()
                    .content(content)
                    .loadedAt(LocalDateTime.now())
                    .build();
        }

        String yamlContent = matcher.group(1);
        String bodyContent = matcher.group(2).trim();

        Map<String, Object> frontmatter;
        try {
            frontmatter = yamlMapper.readValue(yamlContent, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse YAML frontmatter: {}", e.getMessage());
            return Skill.builder()
                    .content(content)
                    .loadedAt(LocalDateTime.now())
                    .build();
        }

        String name = getString(frontmatter, "name", null);
        String description = getString(frontmatter, "description", null);

        if (name == null || name.isBlank()) {
            log.warn("SKILL.md missing required 'name' field");
        }
        if (description == null || description.isBlank()) {
            log.warn("SKILL.md missing required 'description' field");
        }

        return Skill.builder()
                .name(name)
                .description(description)
                .content(bodyContent)
                .loadedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public Skill loadFromDirectory(String skillPath) {
        try {
            // 支持 classpath 和文件系统路径
            if (skillPath.startsWith("classpath:")) {
                return loadFromClasspath(skillPath);
            } else {
                return loadFromFileSystem(Path.of(skillPath));
            }
        } catch (Exception e) {
            log.error("Failed to load skill from directory: {}", skillPath, e);
            throw new RuntimeException("Failed to load skill: " + skillPath, e);
        }
    }

    private Skill loadFromClasspath(String classpathDir) throws IOException {
        // 读取 SKILL.md
        String skillMdPath = classpathDir.endsWith("/") ? classpathDir + "SKILL.md" : classpathDir + "/SKILL.md";
        Resource skillMdResource = resourceResolver.getResource(skillMdPath);

        if (!skillMdResource.exists()) {
            throw new IOException("SKILL.md not found: " + skillMdPath);
        }

        String content = readResource(skillMdResource);
        Skill skill = parse(content);
        skill.setPath(classpathDir);

        // 加载 references/ 目录
        String refsPattern = classpathDir.endsWith("/") ? classpathDir + "references/*.md" : classpathDir + "/references/*.md";
        try {
            Resource[] refResources = resourceResolver.getResources(refsPattern);
            if (refResources.length > 0) {
                Map<String, String> references = new HashMap<>();
                for (Resource ref : refResources) {
                    String filename = ref.getFilename();
                    String refContent = readResource(ref);
                    references.put(filename, refContent);
                }
                skill.setReferences(references);
            }
        } catch (Exception e) {
            log.debug("No references found for skill: {}", classpathDir);
        }

        return skill;
    }

    private Skill loadFromFileSystem(Path skillDir) throws IOException {
        // 读取 SKILL.md
        Path skillMdPath = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillMdPath)) {
            throw new IOException("SKILL.md not found: " + skillMdPath);
        }

        String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
        Skill skill = parse(content);
        skill.setPath(skillDir.toString());

        // 加载 references/ 目录
        Path refsDir = skillDir.resolve("references");
        if (Files.isDirectory(refsDir)) {
            Map<String, String> references = new HashMap<>();
            try (Stream<Path> files = Files.list(refsDir)) {
                files.filter(f -> f.toString().endsWith(".md"))
                        .forEach(f -> {
                            try {
                                references.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                log.warn("Failed to read reference file: {}", f, e);
                            }
                        });
            }
            if (!references.isEmpty()) {
                skill.setReferences(references);
            }
        }

        return skill;
    }

    private String readResource(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}

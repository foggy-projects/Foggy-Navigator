package com.foggy.navigator.agent.framework.skill.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillParser;
import com.foggy.navigator.agent.framework.skill.SkillType;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 默认Skill解析器实现
 * 支持 YAML frontmatter 格式和传统 Markdown 格式
 */
@Slf4j
@Component
public class DefaultSkillParser implements SkillParser {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL
    );

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "@import\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );

    private final Parser markdownParser = Parser.builder().build();
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private final String basePath = "classpath:skills/";

    @Override
    public Skill parse(String markdownContent) {
        // 处理 @import 指令
        String processedContent = processImports(markdownContent);

        // 尝试解析 YAML frontmatter
        Matcher matcher = FRONTMATTER_PATTERN.matcher(processedContent);
        if (matcher.matches()) {
            String yamlContent = matcher.group(1);
            String bodyContent = matcher.group(2);
            return parseWithFrontmatter(yamlContent, bodyContent);
        }

        // 回退到传统 Markdown 格式解析
        return parseLegacyFormat(processedContent);
    }

    @Override
    public Skill parseFile(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            Skill skill = parse(content);
            skill.setMarkdownPath(filePath);
            return skill;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse skill file: " + filePath, e);
        }
    }

    /**
     * 解析 YAML frontmatter 格式
     */
    @SuppressWarnings("unchecked")
    private Skill parseWithFrontmatter(String yamlContent, String markdownContent) {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> frontmatter;
        try {
            frontmatter = yamlMapper.readValue(yamlContent, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse YAML frontmatter: {}", e.getMessage());
            return parseLegacyFormat(markdownContent);
        }

        Skill.SkillBuilder builder = Skill.builder();
        builder.loadedAt(LocalDateTime.now());

        // 基本信息
        String id = getString(frontmatter, "id", null);
        builder.id(id);
        builder.name(getString(frontmatter, "name", id));
        builder.description(getString(frontmatter, "description", null));

        // 类型
        String typeStr = getString(frontmatter, "type", "instruction");
        try {
            builder.type(SkillType.valueOf(typeStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown skill type '{}', defaulting to INSTRUCTION", typeStr);
            builder.type(SkillType.INSTRUCTION);
        }

        // 触发条件
        builder.triggerKeywords(getStringList(frontmatter, "triggers"));
        builder.intents(getStringList(frontmatter, "intents"));

        // 工具定义（可选）
        if (frontmatter.containsKey("tool")) {
            Object toolObj = frontmatter.get("tool");
            try {
                if (toolObj instanceof Map) {
                    builder.toolDefinition(yamlMapper.writeValueAsString(toolObj));
                } else if (toolObj instanceof String) {
                    builder.toolDefinition((String) toolObj);
                }
            } catch (Exception e) {
                log.warn("Failed to serialize tool definition: {}", e.getMessage());
            }
        }

        // 从 Markdown 内容中提取各部分
        parseMarkdownSections(markdownContent, builder);

        builder.rawContent(markdownContent);

        return builder.build();
    }

    /**
     * 解析 Markdown 内容中的各个 section
     */
    private void parseMarkdownSections(String content, Skill.SkillBuilder builder) {
        String[] sections = content.split("\n# ");

        for (String section : sections) {
            String sectionLower = section.toLowerCase();

            if (sectionLower.startsWith("执行逻辑") || sectionLower.startsWith("execution")) {
                builder.executionLogic(extractSectionContent(section));
            } else if (sectionLower.startsWith("输出格式") || sectionLower.startsWith("output")) {
                builder.outputFormat(extractSectionContent(section));
            } else if (sectionLower.startsWith("分派条件") || sectionLower.startsWith("delegation")) {
                builder.delegationCondition(extractSectionContent(section));
            }
        }
    }

    /**
     * 传统格式解析（无 YAML frontmatter）
     */
    private Skill parseLegacyFormat(String markdownContent) {
        Node document = markdownParser.parse(markdownContent);
        Skill.SkillBuilder builder = Skill.builder();
        builder.loadedAt(LocalDateTime.now());
        builder.type(SkillType.INSTRUCTION);  // 默认类型
        builder.rawContent(markdownContent);

        String currentSection = null;
        StringBuilder sectionContent = new StringBuilder();

        Node node = document.getFirstChild();
        while (node != null) {
            if (node instanceof Heading heading) {
                if (currentSection != null) {
                    applySection(builder, currentSection, sectionContent.toString().trim());
                }
                currentSection = extractText(heading).toLowerCase();
                sectionContent = new StringBuilder();
            } else {
                sectionContent.append(extractText(node)).append("\n");
            }
            node = node.getNext();
        }

        if (currentSection != null) {
            applySection(builder, currentSection, sectionContent.toString().trim());
        }

        return builder.build();
    }

    private void applySection(Skill.SkillBuilder builder, String section, String content) {
        switch (section) {
            case "skill id", "id" -> builder.id(content);
            case "skill标题", "标题", "name", "名称" -> builder.name(content);
            case "触发条件", "trigger" -> builder.triggerKeywords(parseList(content));
            case "意图", "intents" -> builder.intents(parseList(content));
            case "描述", "description" -> builder.description(content);
            case "执行逻辑", "execution" -> builder.executionLogic(content);
            case "输出格式", "output" -> builder.outputFormat(content);
            case "分派条件", "delegation" -> builder.delegationCondition(content);
        }
    }

    /**
     * 处理 @import 指令
     */
    private String processImports(String content) {
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String importPath = matcher.group(1);
            String importedContent = loadImportedFile(importPath);
            matcher.appendReplacement(result, Matcher.quoteReplacement(importedContent));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 加载被引用的文件
     */
    private String loadImportedFile(String path) {
        try {
            String resourcePath = path.startsWith("/") ? "classpath:" + path : basePath + path;
            Resource resource = resourceResolver.getResource(resourcePath);

            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            } else {
                log.warn("Imported file not found: {}", path);
                return "<!-- Import not found: " + path + " -->";
            }
        } catch (IOException e) {
            log.error("Failed to load imported file: {}", path, e);
            return "<!-- Import failed: " + path + " -->";
        }
    }

    // ===== Helper methods =====

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String extractSectionContent(String section) {
        String[] lines = section.split("\n");
        if (lines.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            return sb.toString().trim();
        }
        return "";
    }

    private List<String> parseList(String content) {
        List<String> items = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("*")) {
                items.add(line.substring(1).trim());
            } else if (!line.isEmpty()) {
                items.add(line);
            }
        }
        return items;
    }

    private String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        extractTextRecursive(node, sb);
        return sb.toString();
    }

    private void extractTextRecursive(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append("\n");
        } else {
            Node child = node.getFirstChild();
            while (child != null) {
                extractTextRecursive(child, sb);
                child = child.getNext();
            }
        }
    }
}

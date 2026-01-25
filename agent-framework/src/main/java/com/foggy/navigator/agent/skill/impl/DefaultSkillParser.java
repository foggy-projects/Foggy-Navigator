package com.foggy.navigator.agent.skill.impl;

import com.foggy.navigator.agent.skill.Skill;
import com.foggy.navigator.agent.skill.SkillParser;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认Skill解析器实现
 * 从markdown文件解析Skill定义
 */
@Component
public class DefaultSkillParser implements SkillParser {

    private final Parser parser = Parser.builder().build();

    @Override
    public Skill parse(String markdownContent) {
        Node document = parser.parse(markdownContent);
        Skill.SkillBuilder builder = Skill.builder();
        builder.loadedAt(LocalDateTime.now());

        String currentSection = null;
        StringBuilder sectionContent = new StringBuilder();

        Node node = document.getFirstChild();
        while (node != null) {
            if (node instanceof Heading heading) {
                // 保存上一个section的内容
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

        // 保存最后一个section
        if (currentSection != null) {
            applySection(builder, currentSection, sectionContent.toString().trim());
        }

        return builder.build();
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

    private void applySection(Skill.SkillBuilder builder, String section, String content) {
        switch (section) {
            case "skill id", "id" -> builder.id(content);
            case "skill标题", "标题", "name", "名称" -> builder.name(content);
            case "触发条件", "trigger" -> {
                List<String> keywords = parseList(content);
                builder.triggerKeywords(keywords);
            }
            case "意图", "intents" -> builder.intents(parseList(content));
            case "描述", "description" -> builder.description(content);
            case "执行逻辑", "execution" -> builder.executionLogic(content);
            case "输出格式", "output" -> builder.outputFormat(content);
            case "分派条件", "delegation" -> builder.delegationCondition(content);
        }
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

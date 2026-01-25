package com.foggy.navigator.agent.skill.impl;

import com.foggy.navigator.agent.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSkillParserTest {

    private DefaultSkillParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new DefaultSkillParser();
    }

    @Test
    void parse_shouldExtractSkillId() {
        String markdown = """
                # Skill ID
                code-review

                # Name
                Code Review Skill
                """;

        Skill skill = parser.parse(markdown);

        assertEquals("code-review", skill.getId());
    }

    @Test
    void parse_shouldExtractName() {
        String markdown = """
                # Skill标题
                代码审查技能

                # Description
                审查代码质量
                """;

        Skill skill = parser.parse(markdown);

        assertEquals("代码审查技能", skill.getName());
    }

    @Test
    void parse_shouldExtractTriggerKeywords() {
        String markdown = """
                # 触发条件
                - review
                - 审查
                - code review

                # Description
                Some description
                """;

        Skill skill = parser.parse(markdown);

        assertNotNull(skill.getTriggerKeywords());
        assertEquals(3, skill.getTriggerKeywords().size());
        assertTrue(skill.getTriggerKeywords().contains("review"));
        assertTrue(skill.getTriggerKeywords().contains("审查"));
        assertTrue(skill.getTriggerKeywords().contains("code review"));
    }

    @Test
    void parse_shouldExtractIntents() {
        String markdown = """
                # Intents
                - code_review
                - quality_check
                """;

        Skill skill = parser.parse(markdown);

        assertNotNull(skill.getIntents());
        assertEquals(2, skill.getIntents().size());
    }

    @Test
    void parse_shouldExtractDescription() {
        String markdown = """
                # Description
                This is a detailed description
                of the skill capabilities.
                """;

        Skill skill = parser.parse(markdown);

        assertTrue(skill.getDescription().contains("detailed description"));
    }

    @Test
    void parse_shouldExtractExecutionLogic() {
        String markdown = """
                # 执行逻辑
                1. 读取代码文件
                2. 分析代码结构
                3. 生成审查报告
                """;

        Skill skill = parser.parse(markdown);

        assertNotNull(skill.getExecutionLogic());
        assertTrue(skill.getExecutionLogic().contains("读取代码文件"));
    }

    @Test
    void parse_shouldExtractOutputFormat() {
        String markdown = """
                # Output
                返回JSON格式的审查报告
                """;

        Skill skill = parser.parse(markdown);

        assertTrue(skill.getOutputFormat().contains("JSON"));
    }

    @Test
    void parse_shouldExtractDelegationCondition() {
        String markdown = """
                # Delegation
                当需要执行git操作时，分派给git-agent
                """;

        Skill skill = parser.parse(markdown);

        assertTrue(skill.getDelegationCondition().contains("git-agent"));
    }

    @Test
    void parse_shouldHandleCompleteSkillFile() {
        String markdown = """
                # Skill ID
                code-assistant

                # Skill标题
                代码助手

                # 触发条件
                - 写代码
                - 编程
                - coding

                # 意图
                - write_code
                - debug

                # 描述
                帮助用户编写和调试代码

                # 执行逻辑
                1. 理解用户需求
                2. 生成代码
                3. 解释代码

                # 输出格式
                代码块加解释说明

                # 分派条件
                当需要运行代码时，分派给sandbox-agent
                """;

        Skill skill = parser.parse(markdown);

        assertEquals("code-assistant", skill.getId());
        assertEquals("代码助手", skill.getName());
        assertEquals(3, skill.getTriggerKeywords().size());
        assertEquals(2, skill.getIntents().size());
        assertNotNull(skill.getDescription());
        assertNotNull(skill.getExecutionLogic());
        assertNotNull(skill.getOutputFormat());
        assertNotNull(skill.getDelegationCondition());
        assertNotNull(skill.getLoadedAt());
    }

    @Test
    void parseFile_shouldReadFromFile() throws IOException {
        String markdown = """
                # Skill ID
                file-skill

                # Name
                File Skill
                """;
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, markdown);

        Skill skill = parser.parseFile(skillFile.toString());

        assertEquals("file-skill", skill.getId());
        assertEquals("File Skill", skill.getName());
        assertEquals(skillFile.toString(), skill.getMarkdownPath());
    }

    @Test
    void parseFile_shouldThrowForNonExistentFile() {
        assertThrows(RuntimeException.class, () -> {
            parser.parseFile("/non/existent/file.md");
        });
    }

    @Test
    void parse_shouldHandleEmptyContent() {
        Skill skill = parser.parse("");

        assertNotNull(skill);
        assertNull(skill.getId());
    }

    @Test
    void parse_shouldHandleListWithDifferentMarkers() {
        String markdown = """
                # Trigger
                - item with dash
                * item with asterisk
                plain item
                """;

        Skill skill = parser.parse(markdown);

        assertEquals(3, skill.getTriggerKeywords().size());
    }
}

package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultSkillParser 测试
 * 测试 Claude Code 格式的 SKILL.md 解析
 */
class DefaultSkillParserTest {

    private DefaultSkillParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new DefaultSkillParser();
    }

    @Test
    void parse_shouldExtractNameAndDescription() {
        String markdown = """
                ---
                name: code-review
                description: 代码审查技能。当用户需要审查代码质量时使用。
                ---

                # Code Review

                执行代码审查任务。
                """;

        Skill skill = parser.parse(markdown);

        assertEquals("code-review", skill.getName());
        assertEquals("代码审查技能。当用户需要审查代码质量时使用。", skill.getDescription());
        assertNotNull(skill.getContent());
        assertTrue(skill.getContent().contains("Code Review"));
    }

    @Test
    void parse_shouldExtractContent() {
        String markdown = """
                ---
                name: test-skill
                description: Test skill description
                ---

                # Execution Steps

                1. Step one
                2. Step two
                3. Step three
                """;

        Skill skill = parser.parse(markdown);

        assertNotNull(skill.getContent());
        assertTrue(skill.getContent().contains("Step one"));
        assertTrue(skill.getContent().contains("Step two"));
    }

    @Test
    void parse_shouldHandleMissingFrontmatter() {
        String markdown = """
                # Plain Markdown

                No frontmatter here.
                """;

        Skill skill = parser.parse(markdown);

        assertNull(skill.getName());
        assertNull(skill.getDescription());
        assertNotNull(skill.getContent());
        assertTrue(skill.getContent().contains("Plain Markdown"));
    }

    @Test
    void parse_shouldHandleEmptyContent() {
        Skill skill = parser.parse("");

        assertNotNull(skill);
        assertNull(skill.getName());
    }

    @Test
    void parse_shouldSetLoadedAt() {
        String markdown = """
                ---
                name: test
                description: test
                ---
                Content
                """;

        Skill skill = parser.parse(markdown);

        assertNotNull(skill.getLoadedAt());
    }

    @Test
    void loadFromDirectory_shouldLoadSkillMd() throws IOException {
        // Create skill directory structure
        Path skillDir = tempDir.resolve("test-skill");
        Files.createDirectories(skillDir);

        String skillMd = """
                ---
                name: test-skill
                description: A test skill for unit testing
                ---

                # Test Skill

                This is the body content.
                """;
        Files.writeString(skillDir.resolve("SKILL.md"), skillMd);

        Skill skill = parser.loadFromDirectory(skillDir.toString());

        assertEquals("test-skill", skill.getName());
        assertEquals("A test skill for unit testing", skill.getDescription());
        assertTrue(skill.getContent().contains("Test Skill"));
        assertEquals(skillDir.toString(), skill.getPath());
    }

    @Test
    void loadFromDirectory_shouldThrowForMissingSkillMd() {
        Path emptyDir = tempDir.resolve("empty-dir");

        assertThrows(RuntimeException.class, () -> {
            parser.loadFromDirectory(emptyDir.toString());
        });
    }

    @Test
    void parse_shouldHandleMultilineDescription() {
        String markdown = """
                ---
                name: multi-desc
                description: >
                  This is a long description
                  that spans multiple lines
                  in YAML flow style.
                ---

                Content here.
                """;

        Skill skill = parser.parse(markdown);

        assertEquals("multi-desc", skill.getName());
        assertNotNull(skill.getDescription());
        assertTrue(skill.getDescription().contains("long description"));
    }

    @Test
    void parse_shouldHandleYamlWithExtraFields() {
        String markdown = """
                ---
                name: extra-fields
                description: Basic description
                version: 1.0
                author: test
                ---

                Content.
                """;

        // Should not throw - extra fields are ignored
        Skill skill = parser.parse(markdown);

        assertEquals("extra-fields", skill.getName());
        assertEquals("Basic description", skill.getDescription());
    }

    @Test
    void parse_shouldTrimBodyContent() {
        String markdown = """
                ---
                name: trim-test
                description: Test
                ---


                   Trimmed content

                """;

        Skill skill = parser.parse(markdown);

        assertTrue(skill.getContent().startsWith("Trimmed"));
    }
}

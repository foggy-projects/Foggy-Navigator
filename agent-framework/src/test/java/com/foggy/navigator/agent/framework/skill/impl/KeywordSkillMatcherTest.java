package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordSkillMatcherTest {

    private KeywordSkillMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new KeywordSkillMatcher();
    }

    @Test
    void match_shouldReturnNullForNullSkills() {
        assertNull(matcher.match("hello", null));
    }

    @Test
    void match_shouldReturnNullForEmptySkills() {
        assertNull(matcher.match("hello", List.of()));
    }

    @Test
    void match_shouldMatchByName() {
        Skill codeReviewSkill = Skill.builder()
                .name("code-review")
                .description("代码审查技能")
                .build();

        Skill result = matcher.match("请帮我做code-review", List.of(codeReviewSkill));

        assertNotNull(result);
        assertEquals("code-review", result.getName());
    }

    @Test
    void match_shouldMatchByDescription() {
        Skill debugSkill = Skill.builder()
                .name("debug-helper")
                .description("Help debug code issues, fix bugs, and troubleshoot errors")
                .build();

        // Using words that will match description
        Skill result = matcher.match("help debug this issue", List.of(debugSkill));

        assertNotNull(result);
        assertEquals("debug-helper", result.getName());
    }

    @Test
    void match_shouldReturnBestMatch() {
        Skill codeSkill = Skill.builder()
                .name("code-generator")
                .description("Generate code from templates")
                .build();
        Skill codeReviewSkill = Skill.builder()
                .name("code-review")
                .description("Review code quality and standards")
                .build();

        // "code-review" in message should match code-review skill name exactly
        Skill result = matcher.match("please do code-review", List.of(codeSkill, codeReviewSkill));

        assertEquals("code-review", result.getName());
    }

    @Test
    void match_shouldBeCaseInsensitive() {
        Skill skill = Skill.builder()
                .name("test-skill")
                .description("Review CODE quality")
                .build();

        Skill result = matcher.match("code review please", List.of(skill));

        assertNotNull(result);
    }

    @Test
    void match_shouldReturnNullWhenNoMatch() {
        Skill skill = Skill.builder()
                .name("code-review")
                .description("代码审查技能")
                .build();

        Skill result = matcher.match("今天天气怎么样", List.of(skill));

        assertNull(result);
    }

    @Test
    void calculateScore_shouldReturnZeroForEmptySkill() {
        Skill skill = Skill.builder().build();

        double score = matcher.calculateScore("any message", skill);

        assertEquals(0, score);
    }

    @Test
    void calculateScore_shouldScoreByNameMatch() {
        Skill skill = Skill.builder()
                .name("spring-boot")
                .description("Spring Boot 开发")
                .build();

        // Name match should contribute 0.5
        double score = matcher.calculateScore("spring-boot application", skill);

        assertTrue(score >= 0.5, "Score should be at least 0.5 for name match");
    }

    @Test
    void calculateScore_shouldScoreByDescriptionMatch() {
        Skill skill = Skill.builder()
                .name("db-tool")
                .description("Database query and optimization tool")
                .build();

        // Words "query" and "optimization" should match
        double score = matcher.calculateScore("help with query optimization", skill);

        assertTrue(score > 0, "Score should be positive for description match");
    }

    @Test
    void match_shouldRequireMinimumScore() {
        Skill skill = Skill.builder()
                .name("very-specific-skill")
                .description("非常特殊的技能用于处理极其罕见的场景")
                .build();

        // No relevant words should not match
        Skill result = matcher.match("hello world", List.of(skill));

        assertNull(result);
    }

    @Test
    void match_shouldSelectHighestScoringSkill() {
        Skill skill1 = Skill.builder()
                .name("java-spring")
                .description("Java Spring 开发")
                .build();
        Skill skill2 = Skill.builder()
                .name("java")
                .description("Java 开发")
                .build();

        // "java-spring" should match better for message containing "spring"
        Skill result = matcher.match("java-spring application", List.of(skill1, skill2));

        assertEquals("java-spring", result.getName());
    }
}

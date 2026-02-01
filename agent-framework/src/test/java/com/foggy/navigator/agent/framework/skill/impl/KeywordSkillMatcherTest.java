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
    void match_shouldMatchByTriggerKeyword() {
        Skill codeReviewSkill = Skill.builder()
                .id("code-review")
                .triggerKeywords(List.of("review", "审查", "code review"))
                .build();

        Skill result = matcher.match("请帮我review一下这段代码", List.of(codeReviewSkill));

        assertNotNull(result);
        assertEquals("code-review", result.getId());
    }

    @Test
    void match_shouldMatchByIntent() {
        Skill debugSkill = Skill.builder()
                .id("debug")
                .intents(List.of("debug", "fix bug", "错误"))
                .build();

        Skill result = matcher.match("帮我debug这个问题", List.of(debugSkill));

        assertNotNull(result);
        assertEquals("debug", result.getId());
    }

    @Test
    void match_shouldReturnBestMatch() {
        Skill codeSkill = Skill.builder()
                .id("code")
                .triggerKeywords(List.of("code", "编程"))
                .build();
        Skill codeReviewSkill = Skill.builder()
                .id("code-review")
                .triggerKeywords(List.of("code", "review", "审查"))
                .build();

        // "code review" should match code-review better (2/3) than code (1/2)
        Skill result = matcher.match("帮我做code review", List.of(codeSkill, codeReviewSkill));

        assertEquals("code-review", result.getId());
    }

    @Test
    void match_shouldBeCaseInsensitive() {
        Skill skill = Skill.builder()
                .id("test")
                .triggerKeywords(List.of("Review", "CODE"))
                .build();

        Skill result = matcher.match("code review please", List.of(skill));

        assertNotNull(result);
    }

    @Test
    void match_shouldReturnNullWhenNoMatch() {
        Skill skill = Skill.builder()
                .id("code-review")
                .triggerKeywords(List.of("review", "审查"))
                .build();

        Skill result = matcher.match("今天天气怎么样", List.of(skill));

        assertNull(result);
    }

    @Test
    void calculateScore_shouldReturnZeroForNoKeywords() {
        Skill skill = Skill.builder().id("empty").build();

        double score = matcher.calculateScore("any message", skill);

        assertEquals(0, score);
    }

    @Test
    void calculateScore_shouldCalculateCorrectRatio() {
        Skill skill = Skill.builder()
                .id("test")
                .triggerKeywords(List.of("code", "review", "test"))
                .intents(List.of("debug"))
                .build();

        // "code review" matches 2 out of 4 keywords = 0.5
        double score = matcher.calculateScore("code review", skill);

        assertEquals(0.5, score, 0.001);
    }

    @Test
    void calculateScore_shouldCombineKeywordsAndIntents() {
        Skill skill = Skill.builder()
                .id("test")
                .triggerKeywords(List.of("code"))
                .intents(List.of("debug", "fix"))
                .build();

        // "code debug" matches 2 out of 3 = 0.667
        double score = matcher.calculateScore("code debug issue", skill);

        assertEquals(2.0 / 3.0, score, 0.001);
    }

    @Test
    void match_shouldRequireMinimumScore() {
        Skill skill = Skill.builder()
                .id("test")
                .triggerKeywords(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
                .build();

        // Only matching 1 out of 10 = 0.1, which is at the threshold
        Skill result = matcher.match("a", List.of(skill));

        // Score exactly at 0.1 should not match (> 0.1 required)
        assertNull(result);
    }

    @Test
    void match_shouldSelectHighestScoringSkill() {
        Skill skill1 = Skill.builder()
                .id("skill-1")
                .triggerKeywords(List.of("java", "spring"))
                .build();
        Skill skill2 = Skill.builder()
                .id("skill-2")
                .triggerKeywords(List.of("java", "spring", "boot", "mvc"))
                .build();
        Skill skill3 = Skill.builder()
                .id("skill-3")
                .triggerKeywords(List.of("java"))
                .build();

        // "java spring" matches:
        // skill-1: 2/2 = 1.0
        // skill-2: 2/4 = 0.5
        // skill-3: 1/1 = 1.0
        // Should return first one with highest score
        Skill result = matcher.match("java spring", List.of(skill1, skill2, skill3));

        // Both skill-1 and skill-3 have score 1.0, first one wins
        assertEquals("skill-1", result.getId());
    }
}

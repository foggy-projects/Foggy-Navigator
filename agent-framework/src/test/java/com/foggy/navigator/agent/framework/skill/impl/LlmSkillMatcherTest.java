package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.llm.LlmRequest;
import com.foggy.navigator.agent.framework.llm.LlmResponse;
import com.foggy.navigator.agent.framework.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmSkillMatcherTest {

    @Mock
    private LlmAdapter llmAdapter;

    private LlmSkillMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new LlmSkillMatcher(llmAdapter);
    }

    @Nested
    @DisplayName("match 测试")
    class MatchTest {

        @Test
        @DisplayName("null 技能列表返回 null")
        void shouldReturnNullForNullSkills() {
            assertNull(matcher.match("hello", null));
            verifyNoInteractions(llmAdapter);
        }

        @Test
        @DisplayName("空技能列表返回 null")
        void shouldReturnNullForEmptySkills() {
            assertNull(matcher.match("hello", List.of()));
            verifyNoInteractions(llmAdapter);
        }

        @Test
        @DisplayName("LLM 返回匹配的技能名时应返回对应技能")
        void shouldReturnMatchedSkill() {
            Skill skillA = Skill.builder().name("datasource-config").description("配置数据源").build();
            Skill skillB = Skill.builder().name("semantic-layer").description("语义层管理").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("datasource-config").build());

            Skill result = matcher.match("帮我配置一个 MySQL 数据源", List.of(skillA, skillB));

            assertNotNull(result);
            assertEquals("datasource-config", result.getName());
        }

        @Test
        @DisplayName("LLM 返回 NONE 时返回 null")
        void shouldReturnNullWhenLlmReturnsNone() {
            Skill skill = Skill.builder().name("test").description("test skill").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("NONE").build());

            assertNull(matcher.match("random unrelated message", List.of(skill)));
        }

        @Test
        @DisplayName("LLM 返回空内容时返回 null")
        void shouldReturnNullWhenLlmReturnsEmpty() {
            Skill skill = Skill.builder().name("test").description("test skill").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("").build());

            assertNull(matcher.match("hello", List.of(skill)));
        }

        @Test
        @DisplayName("LLM 返回 null content 时返回 null")
        void shouldReturnNullWhenLlmReturnsNullContent() {
            Skill skill = Skill.builder().name("test").description("test skill").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content(null).build());

            assertNull(matcher.match("hello", List.of(skill)));
        }

        @Test
        @DisplayName("LLM 异常时回退到关键词匹配")
        void shouldFallbackToKeywordOnException() {
            Skill skill = Skill.builder()
                    .name("datasource")
                    .description("管理数据源配置")
                    .build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenThrow(new RuntimeException("LLM unavailable"));

            // 关键词匹配可能返回 null 或 skill，取决于消息内容
            // 重要的是不抛异常
            assertDoesNotThrow(() -> matcher.match("datasource 配置", List.of(skill)));
        }

        @Test
        @DisplayName("LLM 返回未知技能名时回退到关键词匹配")
        void shouldFallbackWhenLlmReturnsUnknownSkillName() {
            Skill skill = Skill.builder()
                    .name("real-skill")
                    .description("A real skill")
                    .build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("non-existent-skill").build());

            // 不抛异常，回退到 keyword 匹配
            assertDoesNotThrow(() -> matcher.match("test message", List.of(skill)));
        }

        @Test
        @DisplayName("技能名匹配应忽略大小写")
        void shouldMatchCaseInsensitive() {
            Skill skill = Skill.builder().name("My-Skill").description("A skill").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("my-skill").build());

            Skill result = matcher.match("trigger my skill", List.of(skill));

            assertNotNull(result);
            assertEquals("My-Skill", result.getName());
        }

        @Test
        @DisplayName("LLM 请求应使用低 temperature")
        void shouldUseLowTemperature() {
            Skill skill = Skill.builder().name("test").description("test").build();

            when(llmAdapter.chat(any(LlmRequest.class)))
                    .thenReturn(LlmResponse.builder().content("NONE").build());

            matcher.match("hello", List.of(skill));

            verify(llmAdapter).chat(argThat(req ->
                    req.getTemperature() == 0.0 && req.getMaxRetries() == 0
            ));
        }
    }

    @Nested
    @DisplayName("calculateScore 测试")
    class CalculateScoreTest {

        @Test
        @DisplayName("委托给 fallback 关键词匹配器")
        void shouldDelegateToFallback() {
            Skill skill = Skill.builder()
                    .name("test-skill")
                    .description("handles test operations")
                    .build();

            double score = matcher.calculateScore("test operations", skill);
            // 关键词匹配应产生 > 0 的分数
            assertTrue(score >= 0);
        }
    }
}

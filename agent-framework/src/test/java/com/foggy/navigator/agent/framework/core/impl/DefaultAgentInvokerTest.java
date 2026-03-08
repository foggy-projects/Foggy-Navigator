package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.llm.LlmMessage;
import com.foggy.navigator.agent.framework.router.SessionRouter;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DefaultAgentInvokerTest {

    @Mock
    private AgentRegistry agentRegistry;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private LlmAdapter llmAdapter;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AsyncTaskExecutor agentExecutor;
    @Mock
    private SkillManager skillManager;
    @Mock
    private SessionRouter sessionRouter;

    private DefaultAgentInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new DefaultAgentInvoker(
                agentRegistry, sessionManager, llmAdapter,
                eventPublisher, agentExecutor, skillManager, sessionRouter,
                List.of(), // builtInTools - empty for unit tests
                null, // llmModelManager
                null, // userMemoryManager
                null, // meterRegistry
                null  // agentTaskManager
        );
    }

    @Nested
    @DisplayName("buildEnhancedSystemPrompt 测试")
    class BuildEnhancedSystemPromptTest {

        @Test
        @DisplayName("无技能时返回原始 prompt")
        void shouldReturnBasePromptWhenNoSkills() {
            String basePrompt = "You are a helpful assistant.";

            String result = invoker.buildEnhancedSystemPrompt(basePrompt, List.of());

            assertEquals(basePrompt, result);
        }

        @Test
        @DisplayName("null 技能列表时返回原始 prompt")
        void shouldReturnBasePromptWhenSkillsNull() {
            String basePrompt = "You are a helpful assistant.";

            String result = invoker.buildEnhancedSystemPrompt(basePrompt, null);

            assertEquals(basePrompt, result);
        }

        @Test
        @DisplayName("有技能时应包含技能摘要")
        void shouldIncludeSkillsSummary() {
            String basePrompt = "You are a helpful assistant.";
            List<Skill> skills = List.of(
                    Skill.builder()
                            .name("skill-a")
                            .description("Does A things")
                            .build(),
                    Skill.builder()
                            .name("skill-b")
                            .description("Does B things")
                            .build()
            );

            String result = invoker.buildEnhancedSystemPrompt(basePrompt, skills);

            assertTrue(result.contains(basePrompt));
            assertTrue(result.contains("## Available Skills"));
            assertTrue(result.contains("**skill-a**"));
            assertTrue(result.contains("Does A things"));
            assertTrue(result.contains("**skill-b**"));
            assertTrue(result.contains("Does B things"));
        }

        @Test
        @DisplayName("空 base prompt 时仍应包含技能摘要")
        void shouldIncludeSkillsSummaryEvenWithEmptyBasePrompt() {
            List<Skill> skills = List.of(
                    Skill.builder().name("test-skill").description("Test description").build()
            );

            String result = invoker.buildEnhancedSystemPrompt("", skills);

            assertTrue(result.contains("## Available Skills"));
            assertTrue(result.contains("**test-skill**"));
        }
    }

    @Nested
    @DisplayName("buildSkillInjectionMessage 测试")
    class BuildSkillInjectionMessageTest {

        @Test
        @DisplayName("应包含 Base directory 和技能内容")
        void shouldContainBaseDirectoryAndContent() {
            Skill skill = Skill.builder()
                    .name("test-skill")
                    .path("/path/to/skills/test-skill")
                    .content("# Test Skill\n\nThis is the skill content.")
                    .build();

            LlmMessage result = invoker.buildSkillInjectionMessage(skill);

            assertEquals("system", result.getRole());
            assertTrue(result.getContent().contains("Base directory for this skill: /path/to/skills/test-skill"));
            assertTrue(result.getContent().contains("# Test Skill"));
            assertTrue(result.getContent().contains("This is the skill content."));
        }

        @Test
        @DisplayName("classpath 路径也应正确包含")
        void shouldHandleClasspathPath() {
            Skill skill = Skill.builder()
                    .name("classpath-skill")
                    .path("classpath:skills/tutor/test-skill")
                    .content("Skill content here")
                    .build();

            LlmMessage result = invoker.buildSkillInjectionMessage(skill);

            assertTrue(result.getContent().contains("classpath:skills/tutor/test-skill"));
        }
    }

    @Nested
    @DisplayName("buildMessages 测试")
    class BuildMessagesTest {

        @Test
        @DisplayName("无匹配技能时正常转换消息")
        void shouldConvertMessagesWithoutSkill() {
            List<Message> history = List.of(
                    createMessage("Hello", MessageRole.USER),
                    createMessage("Hi there!", MessageRole.ASSISTANT),
                    createMessage("Help me", MessageRole.USER)
            );

            List<LlmMessage> result = invoker.buildMessages(history, null);

            assertEquals(3, result.size());
            assertEquals("user", result.get(0).getRole());
            assertEquals("Hello", result.get(0).getContent());
            assertEquals("assistant", result.get(1).getRole());
            assertEquals("user", result.get(2).getRole());
        }

        @Test
        @DisplayName("有匹配技能时在最后用户消息前注入")
        void shouldInjectSkillBeforeLastUserMessage() {
            List<Message> history = List.of(
                    createMessage("Hello", MessageRole.USER),
                    createMessage("Hi!", MessageRole.ASSISTANT),
                    createMessage("Use skill", MessageRole.USER)
            );
            Skill skill = Skill.builder()
                    .name("test-skill")
                    .path("/skills/test")
                    .content("Skill instructions")
                    .build();

            List<LlmMessage> result = invoker.buildMessages(history, skill);

            assertEquals(4, result.size());
            // 顺序：user -> assistant -> [skill injection] -> user
            assertEquals("user", result.get(0).getRole());
            assertEquals("assistant", result.get(1).getRole());
            assertEquals("system", result.get(2).getRole()); // Skill injection
            assertTrue(result.get(2).getContent().contains("Base directory"));
            assertTrue(result.get(2).getContent().contains("Skill instructions"));
            assertEquals("user", result.get(3).getRole());
            assertEquals("Use skill", result.get(3).getContent());
        }

        @Test
        @DisplayName("最后消息不是用户消息时不注入技能")
        void shouldNotInjectIfLastMessageIsNotUser() {
            List<Message> history = List.of(
                    createMessage("Hello", MessageRole.USER),
                    createMessage("Response", MessageRole.ASSISTANT)
            );
            Skill skill = Skill.builder()
                    .name("test-skill")
                    .path("/skills/test")
                    .content("Skill instructions")
                    .build();

            List<LlmMessage> result = invoker.buildMessages(history, skill);

            // 技能不会被注入，因为最后一条不是用户消息
            assertEquals(2, result.size());
            assertFalse(result.stream().anyMatch(m -> "system".equals(m.getRole())));
        }

        @Test
        @DisplayName("空历史消息列表应返回空列表")
        void shouldHandleEmptyHistory() {
            List<LlmMessage> result = invoker.buildMessages(List.of(), null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("单条用户消息且匹配技能时应注入")
        void shouldInjectForSingleUserMessage() {
            List<Message> history = List.of(
                    createMessage("Help", MessageRole.USER)
            );
            Skill skill = Skill.builder()
                    .name("help-skill")
                    .path("/skills/help")
                    .content("Help content")
                    .build();

            List<LlmMessage> result = invoker.buildMessages(history, skill);

            assertEquals(2, result.size());
            assertEquals("system", result.get(0).getRole()); // Skill first
            assertEquals("user", result.get(1).getRole());
        }
    }

    private Message createMessage(String content, MessageRole role) {
        return Message.builder()
                .content(content)
                .role(role)
                .build();
    }
}

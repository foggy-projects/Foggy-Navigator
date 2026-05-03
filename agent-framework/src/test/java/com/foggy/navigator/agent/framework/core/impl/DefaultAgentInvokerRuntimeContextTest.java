package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import com.foggy.navigator.agent.framework.core.model.ModelConfig;
import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.llm.LlmRequest;
import com.foggy.navigator.agent.framework.llm.LlmResponse;
import com.foggy.navigator.agent.framework.llm.LlmStreamHandler;
import com.foggy.navigator.agent.framework.llm.ToolCall;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.session.SessionStatus;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.tool.*;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAgentInvokerRuntimeContextTest {

    @Test
    void findAndExecuteTool_injectsRuntimeContext_fromProviders() throws Exception {
        // Build a dummy tool to capture the request
        class DummyTool implements BuiltInTool {
            ToolExecutionRequest capturedRequest;
            @Override
            public String getName() { return "dummy_tool"; }
            @Override
            public String getDescription() { return "dummy"; }
            @Override
            public Map<String, Object> getParameters() { return Map.of(); }
            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                this.capturedRequest = request;
                return ToolExecutionResult.success("ok");
            }
        }
        DummyTool dummyTool = new DummyTool();

        // Build a provider that returns context
        ToolRuntimeContextProvider provider = request -> {
            assertEquals("dummy_tool", request.getToolName());
            assertEquals("session1", request.getSessionId());
            return Map.of("injected_key", "injected_value");
        };

        DefaultAgentInvoker invoker = new DefaultAgentInvoker(
                null, null, null, null, null, null, null,
                List.of(dummyTool), null, null, null, null,
                List.of(provider)
        );

        // We bypass the private method via reflection for testing
        java.lang.reflect.Method method = DefaultAgentInvoker.class.getDeclaredMethod(
                "findAndExecuteTool", com.foggy.navigator.agent.framework.llm.ToolCall.class,
                String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        com.foggy.navigator.agent.framework.llm.ToolCall toolCall =
                new com.foggy.navigator.agent.framework.llm.ToolCall("1", "dummy_tool", Map.of("param", "val"));

        method.invoke(invoker, toolCall, "session1", "agent1", "user1", "tenant1", "task1");

        assertNotNull(dummyTool.capturedRequest);
        Map<String, Object> ctx = dummyTool.capturedRequest.getRuntimeContext();
        assertNotNull(ctx);
        assertEquals("injected_value", ctx.get("injected_key"));
    }

    @Test
    void findAndExecuteTool_providerException_doesNotThrow_andDoesNotFallbackToParams() throws Exception {
        class DummyTool implements BuiltInTool {
            ToolExecutionRequest capturedRequest;
            @Override
            public String getName() { return "dummy_tool"; }
            @Override
            public String getDescription() { return "dummy"; }
            @Override
            public Map<String, Object> getParameters() { return Map.of(); }
            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                this.capturedRequest = request;
                return ToolExecutionResult.success("ok");
            }
        }
        DummyTool dummyTool = new DummyTool();

        ToolRuntimeContextProvider errorProvider = request -> {
            throw new RuntimeException("Simulated provider failure");
        };

        DefaultAgentInvoker invoker = new DefaultAgentInvoker(
                null, null, null, null, null, null, null,
                List.of(dummyTool), null, null, null, null,
                List.of(errorProvider)
        );

        java.lang.reflect.Method method = DefaultAgentInvoker.class.getDeclaredMethod(
                "findAndExecuteTool", com.foggy.navigator.agent.framework.llm.ToolCall.class,
                String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        com.foggy.navigator.agent.framework.llm.ToolCall toolCall =
                new com.foggy.navigator.agent.framework.llm.ToolCall("1", "dummy_tool", Map.of("param", "val"));

        // Should not throw
        method.invoke(invoker, toolCall, "session1", "agent1", "user1", "tenant1", null);

        assertNotNull(dummyTool.capturedRequest);
        Map<String, Object> ctx = dummyTool.capturedRequest.getRuntimeContext();
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty(), "Context should be empty on provider failure");
        assertNull(ctx.get("param"), "Should not fallback to parameters");
    }

    @Test
    void findAndExecuteTool_passesMessageTaskIdToRuntimeContextProvider() throws Exception {
        class DummyTool implements BuiltInTool {
            @Override public String getName() { return "dummy_tool"; }
            @Override public String getDescription() { return "dummy"; }
            @Override public Map<String, Object> getParameters() { return Map.of(); }
            @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.success("ok");
            }
        }

        // Use an array to capture the taskId inside the lambda
        String[] capturedTaskId = new String[1];
        ToolRuntimeContextProvider provider = request -> {
            capturedTaskId[0] = request.getTaskId();
            return Map.of();
        };

        DefaultAgentInvoker invoker = new DefaultAgentInvoker(
                null, null, null, null, null, null, null,
                List.of(new DummyTool()), null, null, null, null,
                List.of(provider)
        );

        java.lang.reflect.Method method = DefaultAgentInvoker.class.getDeclaredMethod(
                "findAndExecuteTool", com.foggy.navigator.agent.framework.llm.ToolCall.class,
                String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        com.foggy.navigator.agent.framework.llm.ToolCall toolCall =
                new com.foggy.navigator.agent.framework.llm.ToolCall("1", "dummy_tool", Map.of());

        method.invoke(invoker, toolCall, "session1", "agent1", "user1", "tenant1", "task-123");
        assertEquals("task-123", capturedTaskId[0]);
    }

    @Test
    void findAndExecuteTool_withoutMessageTaskId_keepsTaskIdNull() throws Exception {
        class DummyTool implements BuiltInTool {
            @Override public String getName() { return "dummy_tool"; }
            @Override public String getDescription() { return "dummy"; }
            @Override public Map<String, Object> getParameters() { return Map.of(); }
            @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.success("ok");
            }
        }

        String[] capturedTaskId = new String[1];
        capturedTaskId[0] = "should-be-null"; // Initial value
        ToolRuntimeContextProvider provider = request -> {
            capturedTaskId[0] = request.getTaskId();
            return Map.of();
        };

        DefaultAgentInvoker invoker = new DefaultAgentInvoker(
                null, null, null, null, null, null, null,
                List.of(new DummyTool()), null, null, null, null,
                List.of(provider)
        );

        java.lang.reflect.Method method = DefaultAgentInvoker.class.getDeclaredMethod(
                "findAndExecuteTool", com.foggy.navigator.agent.framework.llm.ToolCall.class,
                String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        com.foggy.navigator.agent.framework.llm.ToolCall toolCall =
                new com.foggy.navigator.agent.framework.llm.ToolCall("1", "dummy_tool", Map.of());

        method.invoke(invoker, toolCall, "session1", "agent1", "user1", "tenant1", null);
        assertNull(capturedTaskId[0]);
    }

    @Test
    void doInvoke_propagatesUserMessageTaskIdToRuntimeContextProvider() throws Exception {
        class DummyTool implements BuiltInTool {
            @Override public String getName() { return "dummy_tool"; }
            @Override public String getDescription() { return "dummy"; }
            @Override public Map<String, Object> getParameters() { return Map.of(); }
            @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.success("ok");
            }
        }

        String[] capturedTaskId = new String[1];
        ToolRuntimeContextProvider provider = request -> {
            capturedTaskId[0] = request.getTaskId();
            return Map.of();
        };

        DefaultAgentInvoker invoker = new DefaultAgentInvoker(
                agentRegistry(), sessionManager(), llmAdapterWithOneToolCall(), eventPublisher(),
                null, skillManager(), null, List.of(new DummyTool()), null, null, null, null,
                List.of(provider)
        );

        Method method = DefaultAgentInvoker.class.getDeclaredMethod("doInvoke", String.class, String.class, Message.class);
        method.setAccessible(true);

        Message message = Message.builder()
                .sessionId("session1")
                .taskId("business-task-1")
                .content("run tool")
                .build();
        method.invoke(invoker, "session1", "agent1", message);

        assertEquals("business-task-1", capturedTaskId[0]);
    }

    private AgentRegistry agentRegistry() {
        return new AgentRegistry() {
            @Override public void register(AgentConfig config) { }
            @Override public void unregister(String agentId) { }
            @Override public AgentInfo findById(String agentId) {
                return AgentInfo.builder()
                        .id(agentId)
                        .status(AgentStatus.REGISTERED)
                        .config(AgentConfig.builder()
                                .id(agentId)
                                .model(ModelConfig.builder()
                                        .model("test-model")
                                        .systemPrompt("system")
                                        .build())
                                .build())
                        .build();
            }
            @Override public List<AgentInfo> findByCapability(String capability) { return List.of(); }
            @Override public List<AgentInfo> findAll() { return List.of(); }
            @Override public boolean exists(String agentId) { return true; }
            @Override public void updateStatus(String agentId, AgentStatus status) { }
        };
    }

    private SessionManager sessionManager() {
        return new SessionManager() {
            private final List<Message> messages = new ArrayList<>();
            @Override public String createSession(SessionCreateRequest request) { return "session1"; }
            @Override public Session getSession(String sessionId) {
                return Session.builder().id(sessionId).userId("user1").tenantId("tenant1").agentId("agent1").build();
            }
            @Override public void updateStatus(String sessionId, SessionStatus status) { }
            @Override public String addMessage(String sessionId, Message message) {
                messages.add(message);
                return message.getId();
            }
            @Override public List<Message> getRecentMessages(String sessionId, int limit) {
                return List.of(Message.user(sessionId, "run tool"));
            }
            @Override public List<Message> getAllMessages(String sessionId) { return messages; }
            @Override public void closeSession(String sessionId) { }
            @Override public void deleteSession(String sessionId) { }
            @Override public List<Session> findPendingByUser(String userId) { return List.of(); }
            @Override public List<Session> findByUser(String userId) { return List.of(); }
        };
    }

    private LlmAdapter llmAdapterWithOneToolCall() {
        return new LlmAdapter() {
            private int calls;
            @Override public LlmResponse chat(LlmRequest request) {
                calls++;
                if (calls == 1) {
                    return LlmResponse.builder()
                            .toolCalls(List.of(new ToolCall("call1", "dummy_tool", Map.of())))
                            .build();
                }
                return LlmResponse.builder().content("done").build();
            }
            @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
            @Override public String getName() { return "test"; }
            @Override public boolean supportsModel(String model) { return true; }
        };
    }

    private SkillManager skillManager() {
        return new SkillManager() {
            @Override public void loadSkills(String agentId, String directory) { }
            @Override public void registerSkill(Skill skill) { }
            @Override public Skill matchSkill(String userMessage, String agentId) { return null; }
            @Override public List<Skill> getSkillsByAgent(String agentId) { return List.of(); }
            @Override public Skill getSkillByName(String agentId, String skillName) { return null; }
        };
    }

    private ApplicationEventPublisher eventPublisher() {
        return event -> { };
    }
}

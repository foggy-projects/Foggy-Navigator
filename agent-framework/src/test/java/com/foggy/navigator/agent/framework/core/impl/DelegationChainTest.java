package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import com.foggy.navigator.agent.framework.core.model.ModelConfig;
import com.foggy.navigator.agent.framework.llm.*;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.route.RouteAction;
import com.foggy.navigator.agent.framework.protocol.route.RoutePayload;
import com.foggy.navigator.agent.framework.protocol.route.RouteTarget;
import com.foggy.navigator.agent.framework.router.DelegationRequest;
import com.foggy.navigator.agent.framework.router.DelegationResult;
import com.foggy.navigator.agent.framework.router.SessionRouter;
import com.foggy.navigator.agent.framework.session.*;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.agent.framework.tool.builtin.DelegateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 端到端委派链路测试
 * 覆盖：Agent 调用 → 工具执行 → delegate 工具 → 会话路由 → 事件发布
 */
@ExtendWith(MockitoExtension.class)
class DelegationChainTest {

    @Mock private AgentRegistry agentRegistry;
    @Mock private SessionManager sessionManager;
    @Mock private LlmAdapter llmAdapter;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AsyncTaskExecutor agentExecutor;
    @Mock private SkillManager skillManager;
    @Mock private SessionRouter sessionRouter;

    private DelegateTool delegateTool;
    private DefaultAgentInvoker invoker;

    @BeforeEach
    void setUp() {
        delegateTool = new DelegateTool();
        invoker = new DefaultAgentInvoker(
                agentRegistry, sessionManager, llmAdapter,
                eventPublisher, agentExecutor, skillManager, sessionRouter,
                List.of(delegateTool)
        );
    }

    @Nested
    @DisplayName("完整委派链路")
    class FullDelegationChainTest {

        @Test
        @DisplayName("LLM 触发 delegate → 创建新会话 → 发送 ROUTE_REQUEST")
        void shouldCompleteDelegationChain() {
            // 1. 准备 Agent 配置
            String sourceSessionId = "session-001";
            String sourceAgentId = "tutor-agent";

            AgentInfo tutorAgent = AgentInfo.builder()
                    .id(sourceAgentId)
                    .name("Tutor Agent")
                    .status(AgentStatus.ACTIVE)
                    .config(AgentConfig.builder()
                            .id(sourceAgentId)
                            .name("Tutor Agent")
                            .model(ModelConfig.builder()
                                    .model("gpt-4")
                                    .systemPrompt("You are a tutor agent.")
                                    .build())
                            .build())
                    .build();

            when(agentRegistry.findById(sourceAgentId)).thenReturn(tutorAgent);

            // 2. 准备会话和历史消息
            Session session = Session.builder()
                    .id(sourceSessionId)
                    .agentId(sourceAgentId)
                    .userId("user-1")
                    .tenantId("tenant-1")
                    .status(SessionStatus.ACTIVE)
                    .build();
            when(sessionManager.getSession(sourceSessionId)).thenReturn(session);
            when(sessionManager.getRecentMessages(eq(sourceSessionId), anyInt()))
                    .thenReturn(List.of(
                            Message.user(sourceSessionId, "帮我写一个 Java 排序算法")
                    ));
            when(skillManager.getSkillsByAgent(sourceAgentId)).thenReturn(List.of());
            when(skillManager.matchSkill(any(), eq(sourceAgentId))).thenReturn(null);

            // 3. LLM 返回 delegate tool call
            LlmResponse delegateResponse = LlmResponse.builder()
                    .content("好的，我将这个编程任务委派给编程助手。")
                    .toolCalls(List.of(
                            ToolCall.builder()
                                    .id("call-001")
                                    .name("delegate")
                                    .arguments(Map.of(
                                            "targetAgentId", "coding-agent",
                                            "intent", "编写 Java 排序算法",
                                            "context", "{\"language\":\"java\",\"task\":\"sorting\"}"
                                    ))
                                    .build()
                    ))
                    .build();
            when(llmAdapter.chat(any(LlmRequest.class))).thenReturn(delegateResponse);

            // 4. 准备路由结果
            RoutePayload routePayload = RoutePayload.builder()
                    .action(RouteAction.DELEGATE)
                    .target(RouteTarget.builder()
                            .agentId("coding-agent")
                            .agentName("Coding Agent")
                            .sessionId("session-002")
                            .build())
                    .build();
            DelegationResult delegationResult = DelegationResult.builder()
                    .success(true)
                    .newSessionId("session-002")
                    .route(routePayload)
                    .build();
            when(sessionRouter.delegateToAgent(any(DelegationRequest.class)))
                    .thenReturn(delegationResult);

            // 5. 执行 — 使用 invokeAsync 的回调直接调用 doInvoke
            Message userMessage = Message.user(sourceSessionId, "帮我写一个 Java 排序算法");

            // 因为 invokeAsync 是异步的，我们捕获 Runnable 并同步执行
            doAnswer(invocation -> {
                Runnable task = invocation.getArgument(0);
                task.run();
                return null;
            }).when(agentExecutor).execute(any(Runnable.class));

            invoker.invokeAsync(sourceSessionId, sourceAgentId, userMessage);

            // 6. 验证委派请求
            ArgumentCaptor<DelegationRequest> delegationCaptor =
                    ArgumentCaptor.forClass(DelegationRequest.class);
            verify(sessionRouter).delegateToAgent(delegationCaptor.capture());

            DelegationRequest capturedRequest = delegationCaptor.getValue();
            assertEquals(sourceSessionId, capturedRequest.getSourceSessionId());
            assertEquals(sourceAgentId, capturedRequest.getSourceAgentId());
            assertEquals("coding-agent", capturedRequest.getTargetAgentId());
            assertEquals("编写 Java 排序算法", capturedRequest.getIntent());
            assertEquals("user-1", capturedRequest.getUserId());
            assertEquals("tenant-1", capturedRequest.getTenantId());

            // 7. 验证事件发布
            ArgumentCaptor<AgentMessage> eventCaptor = ArgumentCaptor.forClass(AgentMessage.class);
            verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

            List<AgentMessage> events = eventCaptor.getAllValues();

            // 应有 TOOL_CALL_START 和 ROUTE_REQUEST 事件
            boolean hasToolCallStart = events.stream()
                    .anyMatch(e -> e.getType() == MessageType.TOOL_CALL_START);
            boolean hasRouteRequest = events.stream()
                    .anyMatch(e -> e.getType() == MessageType.ROUTE_REQUEST);

            assertTrue(hasToolCallStart, "Should publish TOOL_CALL_START event");
            assertTrue(hasRouteRequest, "Should publish ROUTE_REQUEST event");
        }

        @Test
        @DisplayName("委派失败时应发布 ERROR 事件")
        void shouldPublishErrorOnDelegationFailure() {
            String sessionId = "session-fail";
            String agentId = "tutor-agent";

            AgentInfo agent = AgentInfo.builder()
                    .id(agentId)
                    .config(AgentConfig.builder()
                            .id(agentId)
                            .model(ModelConfig.builder().model("gpt-4").build())
                            .build())
                    .build();
            when(agentRegistry.findById(agentId)).thenReturn(agent);

            Session session = Session.builder()
                    .id(sessionId).userId("u1").tenantId("t1").build();
            when(sessionManager.getSession(sessionId)).thenReturn(session);
            when(sessionManager.getRecentMessages(eq(sessionId), anyInt()))
                    .thenReturn(List.of(Message.user(sessionId, "test")));
            when(skillManager.getSkillsByAgent(agentId)).thenReturn(List.of());
            when(skillManager.matchSkill(any(), eq(agentId))).thenReturn(null);

            when(llmAdapter.chat(any())).thenReturn(LlmResponse.builder()
                    .toolCalls(List.of(ToolCall.builder()
                            .id("call-err")
                            .name("delegate")
                            .arguments(Map.of(
                                    "targetAgentId", "non-existent",
                                    "intent", "test"
                            ))
                            .build()))
                    .build());

            when(sessionRouter.delegateToAgent(any()))
                    .thenReturn(DelegationResult.builder()
                            .success(false)
                            .errorMessage("Target agent not found: non-existent")
                            .build());

            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                    .when(agentExecutor).execute(any());

            invoker.invokeAsync(sessionId, agentId, Message.user(sessionId, "test"));

            ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
            verify(eventPublisher, atLeast(1)).publishEvent(captor.capture());

            boolean hasError = captor.getAllValues().stream()
                    .anyMatch(e -> e.getType() == MessageType.ERROR);
            assertTrue(hasError, "Should publish ERROR event on delegation failure");
        }
    }

    @Nested
    @DisplayName("普通工具调用链路")
    class ToolCallChainTest {

        @Test
        @DisplayName("LLM 调用普通工具 → 执行 → 结果反馈 → LLM 生成最终回复")
        void shouldExecuteToolAndReturnFinalResponse() {
            String sessionId = "session-tool";
            String agentId = "tutor-agent";

            // Mock 一个普通 BuiltInTool
            BuiltInTool listTool = mock(BuiltInTool.class);
            when(listTool.getName()).thenReturn("list_datasources");
            when(listTool.getDescription()).thenReturn("列出数据源");
            when(listTool.getParameters()).thenReturn(Map.of());
            when(listTool.execute(any(ToolExecutionRequest.class)))
                    .thenReturn(ToolExecutionResult.success(List.of(
                            Map.of("id", "ds-1", "name", "MySQL Dev")
                    )));

            invoker = new DefaultAgentInvoker(
                    agentRegistry, sessionManager, llmAdapter,
                    eventPublisher, agentExecutor, skillManager, sessionRouter,
                    List.of(delegateTool, listTool)
            );

            AgentInfo agent = AgentInfo.builder()
                    .id(agentId)
                    .config(AgentConfig.builder()
                            .id(agentId)
                            .model(ModelConfig.builder().model("gpt-4").build())
                            .build())
                    .build();
            when(agentRegistry.findById(agentId)).thenReturn(agent);

            Session session = Session.builder()
                    .id(sessionId).userId("u1").tenantId("t1").build();
            when(sessionManager.getSession(sessionId)).thenReturn(session);
            when(sessionManager.getRecentMessages(eq(sessionId), anyInt()))
                    .thenReturn(List.of(Message.user(sessionId, "查看数据源")));
            when(skillManager.getSkillsByAgent(agentId)).thenReturn(List.of());
            when(skillManager.matchSkill(any(), eq(agentId))).thenReturn(null);

            // 第一次 LLM 调用返回 tool call
            LlmResponse toolCallResponse = LlmResponse.builder()
                    .content("好的，让我查看数据源列表。")
                    .toolCalls(List.of(ToolCall.builder()
                            .id("call-list")
                            .name("list_datasources")
                            .arguments(Map.of())
                            .build()))
                    .build();

            // 第二次 LLM 调用返回纯文本
            LlmResponse finalResponse = LlmResponse.builder()
                    .content("您当前有 1 个数据源：MySQL Dev。")
                    .build();

            when(llmAdapter.chat(any()))
                    .thenReturn(toolCallResponse)
                    .thenReturn(finalResponse);

            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                    .when(agentExecutor).execute(any());

            invoker.invokeAsync(sessionId, agentId, Message.user(sessionId, "查看数据源"));

            // 验证工具被执行
            verify(listTool).execute(any(ToolExecutionRequest.class));

            // 验证最终有 TEXT_COMPLETE 事件
            ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
            verify(eventPublisher, atLeast(1)).publishEvent(captor.capture());

            boolean hasToolStart = captor.getAllValues().stream()
                    .anyMatch(e -> e.getType() == MessageType.TOOL_CALL_START);
            boolean hasToolResult = captor.getAllValues().stream()
                    .anyMatch(e -> e.getType() == MessageType.TOOL_CALL_RESULT);
            boolean hasTextComplete = captor.getAllValues().stream()
                    .anyMatch(e -> e.getType() == MessageType.TEXT_COMPLETE);

            assertTrue(hasToolStart, "Should publish TOOL_CALL_START");
            assertTrue(hasToolResult, "Should publish TOOL_CALL_RESULT");
            assertTrue(hasTextComplete, "Should publish TEXT_COMPLETE");
        }

        @Test
        @DisplayName("工具执行失败时错误信息应反馈给 LLM")
        void shouldFeedbackToolErrorToLlm() {
            String sessionId = "session-tool-err";
            String agentId = "test-agent";

            BuiltInTool failingTool = mock(BuiltInTool.class);
            when(failingTool.getName()).thenReturn("failing_tool");
            when(failingTool.getDescription()).thenReturn("A tool that fails");
            when(failingTool.getParameters()).thenReturn(Map.of());
            when(failingTool.execute(any()))
                    .thenReturn(ToolExecutionResult.error("Connection refused"));

            invoker = new DefaultAgentInvoker(
                    agentRegistry, sessionManager, llmAdapter,
                    eventPublisher, agentExecutor, skillManager, sessionRouter,
                    List.of(delegateTool, failingTool)
            );

            AgentInfo agent = AgentInfo.builder()
                    .id(agentId)
                    .config(AgentConfig.builder()
                            .id(agentId)
                            .model(ModelConfig.builder().model("gpt-4").build())
                            .build())
                    .build();
            when(agentRegistry.findById(agentId)).thenReturn(agent);
            when(sessionManager.getSession(sessionId)).thenReturn(
                    Session.builder().id(sessionId).userId("u1").tenantId("t1").build());
            when(sessionManager.getRecentMessages(eq(sessionId), anyInt()))
                    .thenReturn(List.of(Message.user(sessionId, "run tool")));
            when(skillManager.getSkillsByAgent(agentId)).thenReturn(List.of());
            when(skillManager.matchSkill(any(), eq(agentId))).thenReturn(null);

            // 第一次：tool call
            when(llmAdapter.chat(any()))
                    .thenReturn(LlmResponse.builder()
                            .toolCalls(List.of(ToolCall.builder()
                                    .id("call-fail")
                                    .name("failing_tool")
                                    .arguments(Map.of())
                                    .build()))
                            .build())
                    // 第二次：LLM 收到错误后给出回复
                    .thenReturn(LlmResponse.builder()
                            .content("工具执行失败，请稍后再试。")
                            .build());

            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                    .when(agentExecutor).execute(any());

            invoker.invokeAsync(sessionId, agentId, Message.user(sessionId, "run tool"));

            // 验证第二次 LLM 调用的消息中包含错误信息
            ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
            verify(llmAdapter, times(2)).chat(requestCaptor.capture());

            LlmRequest secondRequest = requestCaptor.getAllValues().get(1);
            List<LlmMessage> messages = secondRequest.getMessages();

            // 最后一条消息应是 tool role，包含错误
            LlmMessage lastMsg = messages.get(messages.size() - 1);
            assertEquals("tool", lastMsg.getRole());
            assertTrue(lastMsg.getContent().contains("Error:"));
            assertTrue(lastMsg.getContent().contains("Connection refused"));
        }
    }

    @Nested
    @DisplayName("tenantId 传递验证")
    class TenantIdPropagationTest {

        @Test
        @DisplayName("工具执行请求应包含 tenantId")
        void shouldPassTenantIdToToolExecution() {
            String sessionId = "session-tenant";
            String agentId = "test-agent";
            String tenantId = "tenant-abc";

            BuiltInTool tool = mock(BuiltInTool.class);
            when(tool.getName()).thenReturn("test_tool");
            when(tool.getDescription()).thenReturn("test");
            when(tool.getParameters()).thenReturn(Map.of());
            when(tool.execute(any())).thenReturn(ToolExecutionResult.success("ok"));

            invoker = new DefaultAgentInvoker(
                    agentRegistry, sessionManager, llmAdapter,
                    eventPublisher, agentExecutor, skillManager, sessionRouter,
                    List.of(delegateTool, tool)
            );

            AgentInfo agent = AgentInfo.builder()
                    .id(agentId)
                    .config(AgentConfig.builder()
                            .id(agentId)
                            .model(ModelConfig.builder().model("gpt-4").build())
                            .build())
                    .build();
            when(agentRegistry.findById(agentId)).thenReturn(agent);
            when(sessionManager.getSession(sessionId)).thenReturn(
                    Session.builder().id(sessionId).userId("u1").tenantId(tenantId).build());
            when(sessionManager.getRecentMessages(eq(sessionId), anyInt()))
                    .thenReturn(List.of(Message.user(sessionId, "test")));
            when(skillManager.getSkillsByAgent(agentId)).thenReturn(List.of());
            when(skillManager.matchSkill(any(), eq(agentId))).thenReturn(null);

            when(llmAdapter.chat(any()))
                    .thenReturn(LlmResponse.builder()
                            .toolCalls(List.of(ToolCall.builder()
                                    .id("call-t")
                                    .name("test_tool")
                                    .arguments(Map.of())
                                    .build()))
                            .build())
                    .thenReturn(LlmResponse.builder().content("done").build());

            doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                    .when(agentExecutor).execute(any());

            invoker.invokeAsync(sessionId, agentId, Message.user(sessionId, "test"));

            ArgumentCaptor<ToolExecutionRequest> toolCaptor =
                    ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(tool).execute(toolCaptor.capture());

            assertEquals(tenantId, toolCaptor.getValue().getTenantId());
            assertEquals("u1", toolCaptor.getValue().getUserId());
            assertEquals(sessionId, toolCaptor.getValue().getSessionId());
        }
    }
}

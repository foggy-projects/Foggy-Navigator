package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.context.ContextWindowManager;
import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import com.foggy.navigator.agent.framework.core.model.ModelConfig;
import com.foggy.navigator.agent.framework.llm.*;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.router.DelegationRequest;
import com.foggy.navigator.agent.framework.router.DelegationResult;
import com.foggy.navigator.agent.framework.router.SessionRouter;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolDefinition;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.agent.framework.tool.builtin.DelegateTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认Agent调用器实现
 * 查找Agent → 构建LlmRequest → 工具执行循环 → 通过事件发布结果
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultAgentInvoker implements AgentInvoker {

    private static final int MAX_TOOL_ITERATIONS = 10;
    private static final int HISTORY_FETCH_LIMIT = 50;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ContextWindowManager contextWindowManager = new ContextWindowManager();

    private final AgentRegistry agentRegistry;
    private final SessionManager sessionManager;
    private final LlmAdapter llmAdapter;
    private final ApplicationEventPublisher eventPublisher;
    private final AsyncTaskExecutor agentExecutor;
    private final SkillManager skillManager;
    private final SessionRouter sessionRouter;
    private final List<BuiltInTool> builtInTools;

    @Override
    public void invokeAsync(String sessionId, String agentId, Message userMessage) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        agentExecutor.execute(() -> {
            if (mdcContext != null) MDC.setContextMap(mdcContext);
            MDC.put("sessionId", sessionId);
            MDC.put("agentId", agentId);
            try {
                doInvoke(sessionId, agentId, userMessage);
            } catch (Exception e) {
                log.error("Agent invocation failed: sessionId={}, agentId={}", sessionId, agentId, e);
                eventPublisher.publishEvent(AgentMessage.of(
                        sessionId, agentId, MessageType.ERROR,
                        Map.of("error", "Agent processing failed: " + e.getMessage())
                ));
            } finally {
                MDC.clear();
            }
        });
    }

    private void doInvoke(String sessionId, String agentId, Message userMessage) {
        long invokeStartTime = System.currentTimeMillis();

        // 1. 查找Agent配置
        AgentInfo agent = agentRegistry.findById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        // 2. 获取历史消息和配置（token-aware 上下文窗口）
        AgentConfig config = agent.getConfig();
        ModelConfig modelConfig = config.getModel();
        int maxContextTokens = modelConfig != null ? modelConfig.getMaxContextTokens() : 8000;
        List<Message> rawHistory = sessionManager.getRecentMessages(sessionId, HISTORY_FETCH_LIMIT);
        List<Message> history = contextWindowManager.selectMessages(rawHistory, maxContextTokens);

        // 3. 获取 Agent 的所有 Skills 并构建增强的 system prompt
        List<Skill> skills = skillManager.getSkillsByAgent(agentId);
        String baseSystemPrompt = modelConfig != null ? modelConfig.getSystemPrompt() : "";
        String enhancedSystemPrompt = buildEnhancedSystemPrompt(baseSystemPrompt, skills);

        // 4. 匹配 Skill（基于用户最新消息）
        String userContent = userMessage != null ? userMessage.getContent() : "";
        Skill matchedSkill = skillManager.matchSkill(userContent, agentId);

        // 5. 构建消息列表（可能包含 Skill 注入）
        List<LlmMessage> messages = buildMessages(history, matchedSkill);

        // 6. 解析工具定义
        List<ToolDefinition> tools = resolveTools(config);

        if (matchedSkill != null) {
            log.info("Skill matched for session {}: {} (path: {})",
                    sessionId, matchedSkill.getName(), matchedSkill.getPath());
        }

        // 7. 获取 userId 和 tenantId（用于工具执行上下文）
        String userId = null;
        String tenantId = null;
        Session currentSession = sessionManager.getSession(sessionId);
        if (currentSession != null) {
            userId = currentSession.getUserId();
            tenantId = currentSession.getTenantId();
        }

        // 8. 工具执行循环
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            LlmRequest request = LlmRequest.builder()
                    .model(modelConfig != null ? modelConfig.getModel() : null)
                    .temperature(modelConfig != null ? modelConfig.getTemperature() : 0.7)
                    .systemPrompt(enhancedSystemPrompt)
                    .messages(new ArrayList<>(messages))
                    .tools(tools)
                    .build();

            LlmResponse response = llmAdapter.chat(request);

            if (!response.hasToolCalls()) {
                // LLM 返回纯文本 → 发布事件，结束循环
                String content = response.getContent() != null ? response.getContent() : "";
                long totalDurationMs = System.currentTimeMillis() - invokeStartTime;
                log.info("Agent invocation completed: sessionId={}, agentId={}, " +
                         "totalDurationMs={}, toolIterations={}, finalContentLength={}",
                        sessionId, agentId, totalDurationMs, iteration + 1,
                        content.length());
                eventPublisher.publishEvent(AgentMessage.of(
                        sessionId, agentId, MessageType.TEXT_COMPLETE,
                        Map.of("content", content)
                ));
                return;
            }

            // 处理 tool calls
            boolean delegated = false;
            List<ToolCall> toolCalls = response.getToolCalls();
            List<ToolExecutionResult> toolResults = new ArrayList<>();

            for (ToolCall toolCall : toolCalls) {
                if (DelegateTool.TOOL_NAME.equals(toolCall.getName())) {
                    // 委派工具 → 特殊处理
                    eventPublisher.publishEvent(AgentMessage.of(
                            sessionId, agentId, MessageType.TOOL_CALL_START,
                            Map.of(
                                    "toolCallId", toolCall.getId(),
                                    "toolName", toolCall.getName(),
                                    "arguments", toolCall.getArguments()
                            )
                    ));
                    handleDelegation(sessionId, agentId, toolCall);
                    delegated = true;
                    break;
                }

                // 普通工具 → 执行并收集结果
                eventPublisher.publishEvent(AgentMessage.of(
                        sessionId, agentId, MessageType.TOOL_CALL_START,
                        Map.of(
                                "toolCallId", toolCall.getId(),
                                "toolName", toolCall.getName(),
                                "arguments", toolCall.getArguments()
                        )
                ));

                ToolExecutionResult result = findAndExecuteTool(toolCall, sessionId, agentId, userId, tenantId);
                toolResults.add(result);

                eventPublisher.publishEvent(AgentMessage.of(
                        sessionId, agentId, MessageType.TOOL_CALL_RESULT,
                        Map.of(
                                "toolCallId", toolCall.getId(),
                                "toolName", toolCall.getName(),
                                "success", result.isSuccess(),
                                "data", result.isSuccess()
                                        ? String.valueOf(result.getData())
                                        : result.getErrorMessage()
                        )
                ));
            }

            if (delegated) {
                return;
            }

            // 将本轮 assistant+toolCalls 和 tool results 追加到 messages
            messages.add(LlmMessage.assistantWithToolCalls(
                    response.getContent(), toolCalls));

            for (int j = 0; j < toolCalls.size(); j++) {
                ToolCall toolCall = toolCalls.get(j);
                ToolExecutionResult result = toolResults.get(j);
                String resultContent = result.isSuccess()
                        ? serializeResult(result.getData())
                        : "Error: " + result.getErrorMessage();
                messages.add(LlmMessage.tool(toolCall.getId(), resultContent));
            }

            log.debug("Tool iteration {} completed, {} tool calls processed", iteration + 1, toolCalls.size());
        }

        log.warn("Max tool iterations ({}) reached for session {}", MAX_TOOL_ITERATIONS, sessionId);
        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.ERROR,
                Map.of("error", "工具调用次数超过上限，请重新描述您的需求。")
        ));
    }

    /**
     * 查找并执行工具
     */
    private ToolExecutionResult findAndExecuteTool(ToolCall toolCall, String sessionId, String agentId, String userId, String tenantId) {
        for (BuiltInTool tool : builtInTools) {
            if (tool.getName().equals(toolCall.getName())) {
                ToolExecutionRequest execRequest = ToolExecutionRequest.builder()
                        .toolName(toolCall.getName())
                        .userId(userId)
                        .tenantId(tenantId)
                        .sessionId(sessionId)
                        .agentId(agentId)
                        .parameters(toolCall.getArguments())
                        .build();
                try {
                    return tool.execute(execRequest);
                } catch (Exception e) {
                    log.error("Tool execution failed: tool={}, error={}", toolCall.getName(), e.getMessage(), e);
                    return ToolExecutionResult.error("Tool execution failed: " + e.getMessage());
                }
            }
        }
        return ToolExecutionResult.error("Unknown tool: " + toolCall.getName());
    }

    private String serializeResult(Object data) {
        if (data == null) return "null";
        if (data instanceof String) return (String) data;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    /**
     * 处理委托请求
     * 当 LLM 调用 delegate 工具时触发
     */
    private void handleDelegation(String sessionId, String agentId, ToolCall toolCall) {
        Map<String, Object> args = toolCall.getArguments();
        String targetAgentId = (String) args.get("targetAgentId");
        String intent = (String) args.get("intent");

        // context 可能是 Map 或 JSON 字符串（因为 DelegateTool 定义为 string 类型）
        Map<String, Object> context;
        Object contextObj = args.get("context");
        if (contextObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) contextObj;
            context = contextMap;
        } else if (contextObj instanceof String contextStr && !contextStr.isBlank()) {
            // 尝试解析 JSON 字符串
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(contextStr, Map.class);
                context = parsed;
            } catch (Exception e) {
                log.warn("Failed to parse context as JSON: {}, using as plain string", contextStr);
                context = Map.of("raw", contextStr);
            }
        } else {
            context = Map.of();
        }

        // 获取当前会话信息
        Session currentSession = sessionManager.getSession(sessionId);
        if (currentSession == null) {
            log.error("Session not found for delegation: {}", sessionId);
            eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.ERROR,
                    Map.of("error", "Session not found for delegation")
            ));
            return;
        }

        // 构建委托请求
        DelegationRequest delegationRequest = DelegationRequest.builder()
                .sourceSessionId(sessionId)
                .sourceAgentId(agentId)
                .targetAgentId(targetAgentId)
                .userId(currentSession.getUserId())
                .tenantId(currentSession.getTenantId())
                .intent(intent)
                .parameters(context)
                .build();

        // 执行委托
        DelegationResult result = sessionRouter.delegateToAgent(delegationRequest);

        if (result.isSuccess()) {
            log.info("Delegation successful: {} -> {} (newSession={})",
                    sessionId, targetAgentId, result.getNewSessionId());

            // 发送 ROUTE_REQUEST 消息给前端
            eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.ROUTE_REQUEST,
                    result.getRoute()
            ));
        } else {
            log.error("Delegation failed: {}", result.getErrorMessage());
            eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.ERROR,
                    Map.of("error", "Delegation failed: " + result.getErrorMessage())
            ));
        }
    }

    /**
     * 构建增强的 system prompt（包含 Skills 摘要列表）
     * Package-private for testing
     */
    String buildEnhancedSystemPrompt(String basePrompt, List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder();
        if (basePrompt != null && !basePrompt.isBlank()) {
            sb.append(basePrompt).append("\n\n");
        }

        sb.append("## Available Skills\n\n");
        sb.append("You have the following skills available. When a user request matches a skill, ");
        sb.append("that skill's detailed instructions will be provided to guide your response.\n\n");

        for (Skill skill : skills) {
            sb.append("- **").append(skill.getName()).append("**: ")
              .append(skill.getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建消息列表，如果匹配到 Skill 则在用户消息前注入
     * Package-private for testing
     */
    List<LlmMessage> buildMessages(List<Message> history, Skill matchedSkill) {
        List<LlmMessage> messages = new ArrayList<>();

        // 转换历史消息，但在最后一条用户消息前插入 Skill 内容
        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);
            boolean isLastUserMessage = (i == history.size() - 1)
                    && msg.getRole() == com.foggy.navigator.agent.framework.session.MessageRole.USER;

            // 在最后一条用户消息前注入 Skill 内容
            if (isLastUserMessage && matchedSkill != null) {
                messages.add(buildSkillInjectionMessage(matchedSkill));
            }

            messages.add(toLlmMessage(msg));
        }

        return messages;
    }

    /**
     * 构建 Skill 注入消息（包含 Base directory）
     * Package-private for testing
     */
    LlmMessage buildSkillInjectionMessage(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("Base directory for this skill: ").append(skill.getPath()).append("\n\n");
        sb.append(skill.getContent());
        return LlmMessage.system(sb.toString());
    }

    private LlmMessage toLlmMessage(Message msg) {
        String role = switch (msg.getRole()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
        return LlmMessage.builder()
                .role(role)
                .content(msg.getContent())
                .build();
    }

    private List<ToolDefinition> resolveTools(AgentConfig config) {
        List<ToolDefinition> tools = new ArrayList<>();

        // 1. 添加内置工具（如 DelegateTool）
        if (builtInTools != null) {
            for (BuiltInTool builtIn : builtInTools) {
                tools.add(ToolDefinition.builder()
                        .name(builtIn.getName())
                        .description(builtIn.getDescription())
                        .parameters(builtIn.getParameters())
                        .build());
            }
        }

        // 2. 添加配置中定义的工具
        if (config.getTools() != null) {
            for (var tool : config.getTools()) {
                tools.add(ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .build());
            }
        }

        log.debug("Resolved {} tools for agent", tools.size());
        return tools;
    }
}

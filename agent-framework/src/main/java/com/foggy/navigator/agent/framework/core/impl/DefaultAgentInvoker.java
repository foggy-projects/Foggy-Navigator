package com.foggy.navigator.agent.framework.core.impl;

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
import com.foggy.navigator.agent.framework.tool.builtin.DelegateTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 默认Agent调用器实现
 * 查找Agent → 构建LlmRequest → 流式调用LLM → 通过事件发布结果
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultAgentInvoker implements AgentInvoker {

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
        agentExecutor.execute(() -> {
            try {
                doInvoke(sessionId, agentId, userMessage);
            } catch (Exception e) {
                log.error("Agent invocation failed: sessionId={}, agentId={}", sessionId, agentId, e);
                eventPublisher.publishEvent(AgentMessage.of(
                        sessionId, agentId, MessageType.ERROR,
                        Map.of("error", "Agent processing failed: " + e.getMessage())
                ));
            }
        });
    }

    private void doInvoke(String sessionId, String agentId, Message userMessage) {
        // 1. 查找Agent配置
        AgentInfo agent = agentRegistry.findById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        // 2. 获取历史消息和配置
        List<Message> history = sessionManager.getRecentMessages(sessionId, 20);
        AgentConfig config = agent.getConfig();
        ModelConfig modelConfig = config.getModel();

        // 3. 获取 Agent 的所有 Skills 并构建增强的 system prompt
        List<Skill> skills = skillManager.getSkillsByAgent(agentId);
        String baseSystemPrompt = modelConfig != null ? modelConfig.getSystemPrompt() : "";
        String enhancedSystemPrompt = buildEnhancedSystemPrompt(baseSystemPrompt, skills);

        // 4. 匹配 Skill（基于用户最新消息）
        String userContent = userMessage != null ? userMessage.getContent() : "";
        Skill matchedSkill = skillManager.matchSkill(userContent, agentId);

        // 5. 构建消息列表（可能包含 Skill 注入）
        List<LlmMessage> messages = buildMessages(history, matchedSkill);

        // 6. 构建 LLM 请求
        LlmRequest request = LlmRequest.builder()
                .model(modelConfig != null ? modelConfig.getModel() : null)
                .temperature(modelConfig != null ? modelConfig.getTemperature() : 0.7)
                .systemPrompt(enhancedSystemPrompt)
                .messages(messages)
                .tools(resolveTools(config))
                .build();

        if (matchedSkill != null) {
            log.info("Skill matched for session {}: {} (path: {})",
                    sessionId, matchedSkill.getName(), matchedSkill.getPath());
        }

        // 7. 流式调用LLM，通过回调发布事件（委托感知）
        llmAdapter.chatStream(request, new DelegationAwareStreamHandler(
                sessionId, agentId, eventPublisher, this::handleDelegation
        ));
    }

    /**
     * 处理委托请求
     * 当 LLM 调用 delegate 工具时触发
     */
    private void handleDelegation(String sessionId, String agentId, ToolCall toolCall) {
        Map<String, Object> args = toolCall.getArguments();
        String targetAgentId = (String) args.get("targetAgentId");
        String intent = (String) args.get("intent");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) args.getOrDefault("context", Map.of());

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

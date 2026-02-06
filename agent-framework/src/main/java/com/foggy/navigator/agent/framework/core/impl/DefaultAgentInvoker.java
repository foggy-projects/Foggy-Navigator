package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import com.foggy.navigator.agent.framework.core.model.ModelConfig;
import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.llm.LlmMessage;
import com.foggy.navigator.agent.framework.llm.LlmRequest;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.tool.ToolDefinition;
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

        // 7. 流式调用LLM，通过回调发布事件
        llmAdapter.chatStream(request, new AgentStreamHandler(
                sessionId, agentId, eventPublisher
        ));
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
        if (config.getTools() == null || config.getTools().isEmpty()) {
            return Collections.emptyList();
        }
        return config.getTools().stream()
                .map(tool -> ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .build())
                .collect(Collectors.toList());
    }
}

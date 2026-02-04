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
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.tool.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

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

        // 2. 构建LLM请求（系统提示 + 历史消息 + 工具定义）
        List<Message> history = sessionManager.getRecentMessages(sessionId, 20);
        AgentConfig config = agent.getConfig();
        ModelConfig modelConfig = config.getModel();

        LlmRequest request = LlmRequest.builder()
                .model(modelConfig != null ? modelConfig.getModel() : null)
                .temperature(modelConfig != null ? modelConfig.getTemperature() : 0.7)
                .systemPrompt(modelConfig != null ? modelConfig.getSystemPrompt() : null)
                .messages(toLlmMessages(history))
                .tools(resolveTools(config))
                .build();

        // 3. 流式调用LLM，通过回调发布事件
        llmAdapter.chatStream(request, new AgentStreamHandler(
                sessionId, agentId, eventPublisher
        ));
    }

    private List<LlmMessage> toLlmMessages(List<Message> history) {
        return history.stream()
                .map(msg -> {
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
                })
                .collect(Collectors.toList());
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

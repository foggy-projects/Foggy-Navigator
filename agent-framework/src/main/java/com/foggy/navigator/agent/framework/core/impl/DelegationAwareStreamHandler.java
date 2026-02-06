package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.llm.LlmResponse;
import com.foggy.navigator.agent.framework.llm.LlmStreamHandler;
import com.foggy.navigator.agent.framework.llm.ToolCall;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.tool.builtin.DelegateTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

/**
 * 委托感知的流式处理器
 * 在 LLM 调用 delegate 工具时，触发委托处理逻辑
 */
@Slf4j
public class DelegationAwareStreamHandler implements LlmStreamHandler {

    private final String sessionId;
    private final String agentId;
    private final ApplicationEventPublisher eventPublisher;
    private final DelegationHandler delegationHandler;

    private boolean delegationTriggered = false;

    @FunctionalInterface
    public interface DelegationHandler {
        void handle(String sessionId, String agentId, ToolCall toolCall);
    }

    public DelegationAwareStreamHandler(String sessionId, String agentId,
                                        ApplicationEventPublisher eventPublisher,
                                        DelegationHandler delegationHandler) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.eventPublisher = eventPublisher;
        this.delegationHandler = delegationHandler;
    }

    @Override
    public void onText(String text) {
        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.TEXT_CHUNK,
                Map.of("content", text)
        ));
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        // 检查是否是 delegate 工具调用
        if (DelegateTool.TOOL_NAME.equals(toolCall.getName())) {
            log.info("Delegate tool call detected: sessionId={}, target={}",
                    sessionId, toolCall.getArguments().get("targetAgentId"));
            delegationTriggered = true;

            // 先发送工具调用开始事件
            eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.TOOL_CALL_START,
                    Map.of(
                            "toolCallId", toolCall.getId(),
                            "toolName", toolCall.getName(),
                            "arguments", toolCall.getArguments()
                    )
            ));

            // 触发委托处理
            delegationHandler.handle(sessionId, agentId, toolCall);
        } else {
            // 其他工具调用，正常发布事件
            eventPublisher.publishEvent(AgentMessage.of(
                    sessionId, agentId, MessageType.TOOL_CALL_START,
                    Map.of(
                            "toolCallId", toolCall.getId(),
                            "toolName", toolCall.getName(),
                            "arguments", toolCall.getArguments()
                    )
            ));
        }
    }

    @Override
    public void onComplete(LlmResponse response) {
        // 如果已触发委托，不发送 TEXT_COMPLETE（让 ROUTE_REQUEST 作为最终消息）
        if (delegationTriggered) {
            log.debug("Skipping TEXT_COMPLETE due to delegation: sessionId={}", sessionId);
            return;
        }

        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.TEXT_COMPLETE,
                Map.of("content", response.getContent() != null ? response.getContent() : "")
        ));
    }

    @Override
    public void onError(Throwable error) {
        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.ERROR,
                Map.of("error", error.getMessage() != null ? error.getMessage() : "Unknown error")
        ));
    }
}

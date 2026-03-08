package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.llm.LlmResponse;
import com.foggy.navigator.agent.framework.llm.LlmStreamHandler;
import com.foggy.navigator.agent.framework.llm.ToolCall;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

/**
 * LLM流式回调 → AgentMessage事件
 * 将LLM的流式响应转换为AgentMessage并通过Spring Event发布
 */
@RequiredArgsConstructor
public class AgentStreamHandler implements LlmStreamHandler {

    private final String sessionId;
    private final String agentId;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onText(String text) {
        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.TEXT_CHUNK,
                Map.of("content", text)
        ));
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        eventPublisher.publishEvent(AgentMessage.of(
                sessionId, agentId, MessageType.TOOL_CALL_START,
                Map.of(
                        "toolCallId", toolCall.getId(),
                        "toolName", toolCall.getName(),
                        "arguments", toolCall.getArguments()
                )
        ));
    }

    @Override
    public void onComplete(LlmResponse response) {
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

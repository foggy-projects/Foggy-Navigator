package com.foggy.navigator.session.event;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 监听AgentMessage事件，持久化 + SSE推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventListener {

    private final SessionManager sessionManager;
    private final UnifiedSseEmitter sseEmitter;

    @Async("sessionEventExecutor")
    @EventListener
    public void onAgentMessage(AgentMessage message) {
        String sessionId = message.getSessionId();
        log.debug("Received AgentMessage: sessionId={}, type={}, agentId={}",
                sessionId, message.getType(), message.getAgentId());

        // 1. 持久化（只保存需要存储的消息类型）
        if (shouldPersist(message.getType())) {
            try {
                Message msg = toSessionMessage(message);
                sessionManager.addMessage(sessionId, msg);
            } catch (Exception e) {
                log.error("Failed to persist message: sessionId={}, type={}", sessionId, message.getType(), e);
            }
        }

        // 2. SSE推送（通过 UnifiedSseEmitter 路由到订阅了该 session 的用户）
        log.debug("Sending SSE event: sessionId={}, type={}", sessionId, message.getType());
        sseEmitter.sendSessionEvent(sessionId, message);
    }

    private boolean shouldPersist(MessageType type) {
        return type != MessageType.TEXT_CHUNK
                && type != MessageType.HEARTBEAT;
    }

    @SuppressWarnings("unchecked")
    private Message toSessionMessage(AgentMessage msg) {
        String content = null;
        Map<String, Object> metadata = new HashMap<>();

        if (msg.getPayload() instanceof Map) {
            Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
            content = (String) payload.getOrDefault("content", null);
            metadata.putAll(payload);
        }
        metadata.put("type", msg.getType().name());
        metadata.put("agentId", msg.getAgentId());

        MessageRole role = (msg.getType() == MessageType.TOOL_CALL_RESULT
                || msg.getType() == MessageType.TOOL_CALL_ERROR)
                ? MessageRole.TOOL
                : MessageRole.ASSISTANT;

        return Message.builder()
                .sessionId(msg.getSessionId())
                .role(role)
                .content(content)
                .metadata(metadata)
                .build();
    }
}

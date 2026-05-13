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
        handleMessage(message);
    }

    /**
     * Synchronous entry point for relays that need message persistence to be
     * visible before they advance task status.
     */
    public void handleMessage(AgentMessage message) {
        String sessionId = message.getSessionId();
        log.debug("Received AgentMessage: sessionId={}, type={}, agentId={}",
                sessionId, message.getType(), message.getAgentId());

        // 1. 持久化（只保存需要存储的消息类型，跳过 result 事件避免重复写入）
        if (shouldPersist(message.getType()) && !isResultEvent(message)) {
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

    /**
     * result 事件（isResult=true）只携带指标（cost/tokens/duration），
     * 其文本内容与前面的 assistant_text 完全一致。
     * 如果也持久化，就会在 DB 中产生重复消息。
     */
    @SuppressWarnings("unchecked")
    private boolean isResultEvent(AgentMessage message) {
        if (message.getType() != MessageType.TEXT_COMPLETE) return false;
        if (message.getPayload() instanceof Map) {
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();
            return Boolean.TRUE.equals(payload.get("isResult"));
        }
        return false;
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

        Message.MessageBuilder builder = Message.builder()
                .id(msg.getMessageId())
                .sessionId(msg.getSessionId())
                .role(role)
                .content(content)
                .metadata(metadata);
        if (msg.getTaskId() != null) {
            builder.taskId(msg.getTaskId());
        }
        return builder.build();
    }
}

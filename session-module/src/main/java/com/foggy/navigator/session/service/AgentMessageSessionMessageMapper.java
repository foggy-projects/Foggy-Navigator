package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the provider-facing event shape to the durable session-message shape.
 *
 * <p>The payload routing service runs before this mapper. This mapper still
 * strips a storage key defensively: a filesystem/object key is an internal
 * implementation detail and must never reach message lists, history restore,
 * or SSE consumers.</p>
 */
@Component
public class AgentMessageSessionMessageMapper {

    public Message toSessionMessage(AgentMessage message) {
        String content = null;
        Map<String, Object> metadata = new LinkedHashMap<>();

        if (message.getPayload() instanceof Map<?, ?> rawPayload) {
            Map<String, Object> payload = SessionMessagePublicPayloadSanitizer.redactInternalStorageKeys(rawPayload);
            content = payload.get("content") instanceof String value ? value : null;
            metadata.putAll(payload);
        }
        metadata.put("type", message.getType().name());
        metadata.put("agentId", message.getAgentId());

        MessageRole role = (message.getType() == MessageType.TOOL_CALL_RESULT
                || message.getType() == MessageType.TOOL_CALL_ERROR)
                ? MessageRole.TOOL
                : MessageRole.ASSISTANT;

        Message.MessageBuilder builder = Message.builder()
                .id(message.getMessageId())
                .sessionId(message.getSessionId())
                .role(role)
                .content(content)
                .metadata(metadata);
        if (message.getTaskId() != null) {
            builder.taskId(message.getTaskId());
        }
        return builder.build();
    }

}

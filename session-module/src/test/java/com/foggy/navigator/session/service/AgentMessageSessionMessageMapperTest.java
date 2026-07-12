package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentMessageSessionMessageMapperTest {

    private final AgentMessageSessionMessageMapper mapper = new AgentMessageSessionMessageMapper();

    @Test
    void mapsToolPreviewButNeverLeaksBackendStorageKey() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("payloadId", "payload-public-id");
        descriptor.put("status", "READY");
        descriptor.put("storageKey", "secret-file-name.gz");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", "bounded preview");
        payload.put("storageKey", "root-secret-file-name.gz");
        payload.put("payloadDescriptor", descriptor);
        payload.put("nested", Map.of("storageKey", "nested-secret-file-name.gz", "visible", "yes"));

        AgentMessage event = AgentMessage.builder()
                .messageId("message-1")
                .sessionId("session-1")
                .taskId("task-1")
                .agentId("codex-worker")
                .type(MessageType.TOOL_CALL_RESULT)
                .payload(payload)
                .build();

        Message message = mapper.toSessionMessage(event);

        assertEquals("message-1", message.getId());
        assertEquals("task-1", message.getTaskId());
        assertEquals(MessageRole.TOOL, message.getRole());
        assertEquals("bounded preview", message.getMetadata().get("data"));
        assertFalse(message.getMetadata().containsKey("storageKey"));
        Map<?, ?> publicDescriptor = (Map<?, ?>) message.getMetadata().get("payloadDescriptor");
        assertEquals("READY", publicDescriptor.get("status"));
        assertFalse(publicDescriptor.containsKey("storageKey"));
        Map<?, ?> nested = (Map<?, ?>) message.getMetadata().get("nested");
        assertEquals("yes", nested.get("visible"));
        assertFalse(nested.containsKey("storageKey"));
    }

    @Test
    void mapsToolPreviewButNeverLeaksSnakeCaseBackendStorageKey() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("payloadId", "payload-public-id");
        descriptor.put("storage_key", "secret-file-name.gz");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("storage_key", "root-secret-file-name.gz");
        payload.put("payloadDescriptor", descriptor);
        payload.put("nested", Map.of("storage_key", "nested-secret-file-name.gz", "visible", "yes"));

        AgentMessage event = AgentMessage.builder()
                .messageId("message-snake-case")
                .sessionId("session-1")
                .agentId("generic-worker")
                .type(MessageType.TOOL_CALL_RESULT)
                .payload(payload)
                .build();

        Message message = mapper.toSessionMessage(event);

        assertFalse(message.getMetadata().containsKey("storage_key"));
        Map<?, ?> publicDescriptor = (Map<?, ?>) message.getMetadata().get("payloadDescriptor");
        assertFalse(publicDescriptor.containsKey("storage_key"));
        Map<?, ?> nested = (Map<?, ?>) message.getMetadata().get("nested");
        assertEquals("yes", nested.get("visible"));
        assertFalse(nested.containsKey("storage_key"));
    }

    @Test
    void preservesLargeFinalAssistantContentWithoutToolPreviewRules() {
        String fullReply = "多字节最终回复\"\\".repeat(10_000);
        AgentMessage event = AgentMessage.builder()
                .messageId("final-message-1")
                .sessionId("session-1")
                .agentId("codex-worker")
                .type(MessageType.SESSION_END)
                .payload(Map.of("content", fullReply, "isResult", true))
                .build();

        Message message = mapper.toSessionMessage(event);

        assertEquals(MessageRole.ASSISTANT, message.getRole());
        assertEquals(fullReply, message.getContent());
        assertEquals(fullReply, message.getMetadata().get("content"));
    }
}

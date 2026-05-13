package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

/**
 * 会话消息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionMessage {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String messageId;
    private String contextId;
    private String taskId;
    private String role;
    private String type;
    private String content;
    private Boolean terminal;
    private String terminalStatus;
    private Map<String, Object> metadata;
    private String createdAt;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getTerminal() { return terminal; }
    public void setTerminal(Boolean terminal) { this.terminal = terminal; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
    public Map<String, Object> getMetadata() { return metadata; }
    @SuppressWarnings("unchecked")
    public void setMetadata(Object metadata) {
        if (metadata instanceof Map<?, ?> map) {
            this.metadata = (Map<String, Object>) map;
            return;
        }
        if (metadata instanceof String text && !text.isBlank()) {
            try {
                this.metadata = OBJECT_MAPPER.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
                this.metadata = null;
            }
        }
    }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "SessionMessage{messageId='" + messageId + "', role='" + role +
                "', type='" + type + "', taskId='" + taskId + "'}";
    }
}

package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
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
    private String eventKind;
    private String progressType;
    private String content;
    private String status;
    private Boolean terminal;
    private String terminalStatus;
    private Map<String, Object> metadata;
    private Object structuredOutput;
    private List<TaskEvidence.ReportRef> reportRefs;
    private List<TaskEvidence.ArtifactRef> artifactRefs;
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
    public String getEventKind() { return eventKind; }
    public void setEventKind(String eventKind) { this.eventKind = eventKind; }
    public String getProgressType() { return progressType; }
    public void setProgressType(String progressType) { this.progressType = progressType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public Object getStructuredOutput() { return structuredOutput; }
    public void setStructuredOutput(Object structuredOutput) { this.structuredOutput = structuredOutput; }
    public List<TaskEvidence.ReportRef> getReportRefs() { return reportRefs; }
    public void setReportRefs(List<TaskEvidence.ReportRef> reportRefs) { this.reportRefs = reportRefs; }
    public List<TaskEvidence.ArtifactRef> getArtifactRefs() { return artifactRefs; }
    public void setArtifactRefs(List<TaskEvidence.ArtifactRef> artifactRefs) { this.artifactRefs = artifactRefs; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "SessionMessage{messageId='" + messageId + "', role='" + role +
                "', type='" + type + "', eventKind='" + eventKind + "', taskId='" + taskId + "'}";
    }
}

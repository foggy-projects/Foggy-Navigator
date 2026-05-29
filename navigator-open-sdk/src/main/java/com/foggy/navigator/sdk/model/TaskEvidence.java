package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskEvidence {
    private String taskId;
    private String agentId;
    private String contextId;
    private String status;
    private boolean terminal;
    private String terminalStatus;
    private FinalAnswer finalAnswer;
    private StructuredOutput structuredOutput;
    private List<ReportRef> reportRefs;
    private List<ArtifactRef> artifactRefs;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
    public FinalAnswer getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(FinalAnswer finalAnswer) { this.finalAnswer = finalAnswer; }
    public StructuredOutput getStructuredOutput() { return structuredOutput; }
    public void setStructuredOutput(StructuredOutput structuredOutput) { this.structuredOutput = structuredOutput; }
    public List<ReportRef> getReportRefs() { return reportRefs; }
    public void setReportRefs(List<ReportRef> reportRefs) { this.reportRefs = reportRefs; }
    public List<ArtifactRef> getArtifactRefs() { return artifactRefs; }
    public void setArtifactRefs(List<ArtifactRef> artifactRefs) { this.artifactRefs = artifactRefs; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinalAnswer {
        private Boolean available;
        private String summary;
        private String messageId;
        private String source;
        private LocalDateTime createdAt;

        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StructuredOutput {
        private Boolean available;
        private Object value;
        private String source;

        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportRef {
        private String type;
        private String ref;
        private String frameId;
        private String summary;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getRef() { return ref; }
        public void setRef(String ref) { this.ref = ref; }
        public String getFrameId() { return frameId; }
        public void setFrameId(String frameId) { this.frameId = frameId; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArtifactRef {
        private String path;
        private String ref;
        private String summary;
        private String hash;
        private String mtime;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getRef() { return ref; }
        public void setRef(String ref) { this.ref = ref; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getHash() { return hash; }
        public void setHash(String hash) { this.hash = hash; }
        public String getMtime() { return mtime; }
        public void setMtime(String mtime) { this.mtime = mtime; }
    }
}

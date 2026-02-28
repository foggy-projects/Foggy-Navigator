package com.foggy.navigator.spi.assistant;

/**
 * 任务助手配置 DTO
 */
public class TaskAssistantConfig {
    private String userId;
    private String workerId;
    private String directoryId;
    private String claudeSessionId;
    private String foggySessionId;
    private Boolean enabled;

    public TaskAssistantConfig() {}

    public TaskAssistantConfig(String userId, String workerId, String directoryId,
                                String claudeSessionId, String foggySessionId, Boolean enabled) {
        this.userId = userId;
        this.workerId = workerId;
        this.directoryId = directoryId;
        this.claudeSessionId = claudeSessionId;
        this.foggySessionId = foggySessionId;
        this.enabled = enabled;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }

    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }

    public String getFoggySessionId() { return foggySessionId; }
    public void setFoggySessionId(String foggySessionId) { this.foggySessionId = foggySessionId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private String workerId;
        private String directoryId;
        private String claudeSessionId;
        private String foggySessionId;
        private Boolean enabled;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder workerId(String workerId) { this.workerId = workerId; return this; }
        public Builder directoryId(String directoryId) { this.directoryId = directoryId; return this; }
        public Builder claudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; return this; }
        public Builder foggySessionId(String foggySessionId) { this.foggySessionId = foggySessionId; return this; }
        public Builder enabled(Boolean enabled) { this.enabled = enabled; return this; }

        public TaskAssistantConfig build() {
            return new TaskAssistantConfig(userId, workerId, directoryId, claudeSessionId, foggySessionId, enabled);
        }
    }
}

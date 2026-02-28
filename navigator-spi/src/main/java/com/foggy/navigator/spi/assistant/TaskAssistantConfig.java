package com.foggy.navigator.spi.assistant;

/**
 * 任务助手配置 DTO
 */
public class TaskAssistantConfig {
    private String userId;
    private Boolean enabled;
    private String foggySessionId;

    // Deprecated fields — kept for API backward compatibility, always null
    private String workerId;
    private String directoryId;
    private String claudeSessionId;

    public TaskAssistantConfig() {}

    public TaskAssistantConfig(String userId, Boolean enabled, String foggySessionId) {
        this.userId = userId;
        this.enabled = enabled;
        this.foggySessionId = foggySessionId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getFoggySessionId() { return foggySessionId; }
    public void setFoggySessionId(String foggySessionId) { this.foggySessionId = foggySessionId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }

    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private Boolean enabled;
        private String foggySessionId;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder enabled(Boolean enabled) { this.enabled = enabled; return this; }
        public Builder foggySessionId(String foggySessionId) { this.foggySessionId = foggySessionId; return this; }

        // Deprecated builder methods — kept for backward compatibility
        public Builder workerId(String workerId) { return this; }
        public Builder directoryId(String directoryId) { return this; }
        public Builder claudeSessionId(String claudeSessionId) { return this; }

        public TaskAssistantConfig build() {
            return new TaskAssistantConfig(userId, enabled, foggySessionId);
        }
    }
}

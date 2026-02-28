package com.foggy.navigator.task.assistant.spi;

/**
 * 任务助手配置 DTO
 */
public class TaskAssistantConfig {
    private String userId;
    private Boolean enabled;
    private String foggySessionId;
    private String workerId;
    private String directoryId;
    private String claudeSessionId;
    private String cwd;
    private String model;

    public TaskAssistantConfig() {}

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

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private Boolean enabled;
        private String foggySessionId;
        private String workerId;
        private String directoryId;
        private String claudeSessionId;
        private String cwd;
        private String model;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder enabled(Boolean enabled) { this.enabled = enabled; return this; }
        public Builder foggySessionId(String foggySessionId) { this.foggySessionId = foggySessionId; return this; }
        public Builder workerId(String workerId) { this.workerId = workerId; return this; }
        public Builder directoryId(String directoryId) { this.directoryId = directoryId; return this; }
        public Builder claudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; return this; }
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }
        public Builder model(String model) { this.model = model; return this; }

        public TaskAssistantConfig build() {
            TaskAssistantConfig config = new TaskAssistantConfig();
            config.userId = userId;
            config.enabled = enabled;
            config.foggySessionId = foggySessionId;
            config.workerId = workerId;
            config.directoryId = directoryId;
            config.claudeSessionId = claudeSessionId;
            config.cwd = cwd;
            config.model = model;
            return config;
        }
    }
}

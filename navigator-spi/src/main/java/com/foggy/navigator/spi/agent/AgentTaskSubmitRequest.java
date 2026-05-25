package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aMessage;

import java.util.List;
import java.util.Map;

/**
 * Complex Agent task submission request.
 * <p>
 * Unlike {@link A2aAgent#sendTask(A2aMessage)}, this request represents the
 * application-level task lifecycle: identity context, model/workspace/worker
 * choices, execution policy and metadata are still part of the dispatch
 * contract.
 */
public class AgentTaskSubmitRequest {

    private String agentId;
    private String providerType;
    private AgentResolveContext resolveContext;
    private A2aMessage message;
    private String sessionId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private String modelConfigId;
    private Integer maxTurns;
    private String permissionMode;
    private List<String> images;
    private List<Map<String, Object>> attachments;
    private String agentTeamsConfigId;
    private String agentTeamsJson;
    private String contextId;
    private Map<String, Object> context;
    private String contextAlias;
    private Map<String, Object> metadata;

    public AgentTaskSubmitRequest() {
    }

    private AgentTaskSubmitRequest(Builder builder) {
        this.agentId = builder.agentId;
        this.providerType = builder.providerType;
        this.resolveContext = builder.resolveContext;
        this.message = builder.message;
        this.sessionId = builder.sessionId;
        this.workerId = builder.workerId;
        this.prompt = builder.prompt;
        this.cwd = builder.cwd;
        this.directoryId = builder.directoryId;
        this.model = builder.model;
        this.modelConfigId = builder.modelConfigId;
        this.maxTurns = builder.maxTurns;
        this.permissionMode = builder.permissionMode;
        this.images = builder.images;
        this.attachments = builder.attachments;
        this.agentTeamsConfigId = builder.agentTeamsConfigId;
        this.agentTeamsJson = builder.agentTeamsJson;
        this.contextId = builder.contextId;
        this.context = builder.context;
        this.contextAlias = builder.contextAlias;
        this.metadata = builder.metadata;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public AgentResolveContext getResolveContext() { return resolveContext; }
    public void setResolveContext(AgentResolveContext resolveContext) { this.resolveContext = resolveContext; }

    public A2aMessage getMessage() { return message; }
    public void setMessage(A2aMessage message) { this.message = message; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }

    public Integer getMaxTurns() { return maxTurns; }
    public void setMaxTurns(Integer maxTurns) { this.maxTurns = maxTurns; }

    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) { this.attachments = attachments; }

    public String getAgentTeamsConfigId() { return agentTeamsConfigId; }
    public void setAgentTeamsConfigId(String agentTeamsConfigId) { this.agentTeamsConfigId = agentTeamsConfigId; }

    public String getAgentTeamsJson() { return agentTeamsJson; }
    public void setAgentTeamsJson(String agentTeamsJson) { this.agentTeamsJson = agentTeamsJson; }

    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getContextAlias() { return contextAlias; }
    public void setContextAlias(String contextAlias) { this.contextAlias = contextAlias; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentId;
        private String providerType;
        private AgentResolveContext resolveContext;
        private A2aMessage message;
        private String sessionId;
        private String workerId;
        private String prompt;
        private String cwd;
        private String directoryId;
        private String model;
        private String modelConfigId;
        private Integer maxTurns;
        private String permissionMode;
        private List<String> images;
        private List<Map<String, Object>> attachments;
        private String agentTeamsConfigId;
        private String agentTeamsJson;
        private String contextId;
        private Map<String, Object> context;
        private String contextAlias;
        private Map<String, Object> metadata;

        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder providerType(String providerType) { this.providerType = providerType; return this; }
        public Builder resolveContext(AgentResolveContext resolveContext) { this.resolveContext = resolveContext; return this; }
        public Builder message(A2aMessage message) { this.message = message; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder workerId(String workerId) { this.workerId = workerId; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }
        public Builder directoryId(String directoryId) { this.directoryId = directoryId; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder modelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; return this; }
        public Builder maxTurns(Integer maxTurns) { this.maxTurns = maxTurns; return this; }
        public Builder permissionMode(String permissionMode) { this.permissionMode = permissionMode; return this; }
        public Builder images(List<String> images) { this.images = images; return this; }
        public Builder attachments(List<Map<String, Object>> attachments) { this.attachments = attachments; return this; }
        public Builder agentTeamsConfigId(String agentTeamsConfigId) { this.agentTeamsConfigId = agentTeamsConfigId; return this; }
        public Builder agentTeamsJson(String agentTeamsJson) { this.agentTeamsJson = agentTeamsJson; return this; }
        public Builder contextId(String contextId) { this.contextId = contextId; return this; }
        public Builder context(Map<String, Object> context) { this.context = context; return this; }
        public Builder contextAlias(String contextAlias) { this.contextAlias = contextAlias; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public AgentTaskSubmitRequest build() { return new AgentTaskSubmitRequest(this); }
    }
}

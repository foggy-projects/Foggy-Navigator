package com.foggy.navigator.spi.agent;

/**
 * Agent 解析上下文，统一承载 user/tenant/session/来源 等维度信息，
 * 避免 Provider 接口因新维度不断增加重载方法。
 * <p>
 * 放在 navigator-spi（无 Lombok），手写 getter/setter/builder。
 */
public class AgentResolveContext {

    /** 用户 ID（UI / A2A 场景） */
    private String userId;

    /** 租户 ID（OpenAPI 场景） */
    private String tenantId;

    /** 平台会话 ID（可空） */
    private String sessionId;

    /** 请求来源：UI / OPEN_API / A2A / SYSTEM */
    private String requestSource;

    public AgentResolveContext() {}

    private AgentResolveContext(Builder builder) {
        this.userId = builder.userId;
        this.tenantId = builder.tenantId;
        this.sessionId = builder.sessionId;
        this.requestSource = builder.requestSource;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRequestSource() { return requestSource; }
    public void setRequestSource(String requestSource) { this.requestSource = requestSource; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private String tenantId;
        private String sessionId;
        private String requestSource;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder requestSource(String requestSource) { this.requestSource = requestSource; return this; }

        public AgentResolveContext build() { return new AgentResolveContext(this); }
    }
}

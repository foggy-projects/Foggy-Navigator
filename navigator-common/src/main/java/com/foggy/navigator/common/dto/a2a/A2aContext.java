package com.foggy.navigator.common.dto.a2a;

import lombok.Builder;
import lombok.Data;

/**
 * A2A Pipeline 上下文 — 由 ContextResolvingA2aAgent 解析后传递给 InnerA2aAgent
 */
@Data
@Builder
public class A2aContext {
    private A2aMessage message;
    private String contextId;
    private String contextAlias;
    private String agentSessionRef;      // 已解析的 claudeSessionId
    private String navigatorSessionId;   // 已解析的平台 session ID
    private String userId;
    private String tenantId;
    private String agentId;
}

package com.foggy.navigator.agent.framework.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 会话创建请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCreateRequest {
    private String userId;
    private String tenantId;
    private String agentId;
    private String providerType;
    private String parentSessionId;
    private String taskName;
    private Map<String, Object> context;
}

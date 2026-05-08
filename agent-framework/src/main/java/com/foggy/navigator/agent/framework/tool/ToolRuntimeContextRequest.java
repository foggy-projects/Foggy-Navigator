package com.foggy.navigator.agent.framework.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Request object for obtaining tool runtime context variables.
 */
@Data
@Builder
public class ToolRuntimeContextRequest {
    private String toolName;
    private String sessionId;
    private String taskId;
    private String agentId;
    private String userId;
    private String tenantId;
    private Map<String, Object> parameters;
}

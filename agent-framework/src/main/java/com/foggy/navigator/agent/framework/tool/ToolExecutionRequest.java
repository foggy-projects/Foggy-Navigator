package com.foggy.navigator.agent.framework.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private String toolName;
    private String userId;
    private String tenantId;
    private String sessionId;
    private String agentId;
    private Map<String, Object> parameters;
}

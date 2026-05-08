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

    /**
     * Framework-injected runtime context. NOT visible to LLM tool schemas.
     * Use this to pass secrets (e.g. task_scoped_token) from the Worker runtime
     * to tools without exposing them to the LLM.
     */
    private Map<String, Object> runtimeContext;
}

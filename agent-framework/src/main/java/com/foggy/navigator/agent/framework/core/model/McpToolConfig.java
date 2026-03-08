package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP工具配置（系统级，不含用户凭证）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolConfig {
    private String name;
    private String description;
    private String mcpServerUrl;
    private String protocol;  // stdio / sse / streamable-http
    private List<String> capabilities;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private boolean requiresAuth;
}

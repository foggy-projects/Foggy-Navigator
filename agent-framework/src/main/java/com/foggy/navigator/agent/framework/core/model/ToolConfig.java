package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolConfig {
    private String name;
    private String description;
    private HttpToolConfig http;
    private McpToolConfig mcp;
}

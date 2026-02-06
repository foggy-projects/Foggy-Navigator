package com.foggy.navigator.agent.framework.tool;

import com.foggy.navigator.agent.framework.core.model.HttpToolConfig;
import com.foggy.navigator.agent.framework.core.model.McpToolConfig;

import java.util.List;

/**
 * 工具注册表
 * 支持内置工具、HTTP工具和MCP工具，实现用户级别的凭证隔离
 */
public interface ToolRegistry {

    // ===== 内置工具注册 =====

    void registerBuiltInTool(BuiltInTool tool);

    List<ToolDefinition> getBuiltInTools();

    // ===== Agent级工具注册 =====

    void registerHttpTool(String agentId, String name, String description, HttpToolConfig config);

    void registerMcpTool(String agentId, McpToolConfig config);

    // ===== 用户凭证管理 =====

    void bindUserCredential(String userId, String toolName, UserToolCredential credential);

    void unbindUserCredential(String userId, String toolName);

    UserToolCredential getUserCredential(String userId, String toolName);

    // ===== 工具查询 =====

    List<ToolDefinition> getToolsByAgent(String agentId);

    List<ToolDefinition> getAvailableTools(String agentId, String userId);

    // ===== 工具执行 =====

    ToolExecutionResult executeTool(ToolExecutionRequest request);
}

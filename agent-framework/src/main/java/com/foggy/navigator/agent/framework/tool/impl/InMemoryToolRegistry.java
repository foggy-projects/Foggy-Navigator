package com.foggy.navigator.agent.framework.tool.impl;

import com.foggy.navigator.agent.framework.core.model.HttpToolConfig;
import com.foggy.navigator.agent.framework.core.model.McpToolConfig;
import com.foggy.navigator.agent.framework.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存实现的工具注册表
 */
@Slf4j
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private final CredentialStore credentialStore;

    // 内置工具（对所有 Agent 可用）
    private final ConcurrentHashMap<String, BuiltInTool> builtInTools = new ConcurrentHashMap<>();

    // agentId -> List<ToolEntry>
    private final ConcurrentHashMap<String, List<ToolEntry>> agentTools = new ConcurrentHashMap<>();

    public InMemoryToolRegistry(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public void registerBuiltInTool(BuiltInTool tool) {
        builtInTools.put(tool.getName(), tool);
        log.info("Registered built-in tool: {}", tool.getName());
    }

    @Override
    public List<ToolDefinition> getBuiltInTools() {
        return builtInTools.values().stream()
                .map(tool -> ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(tool.getParameters())
                        .requiresAuth(false)
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void registerHttpTool(String agentId, String name, String description, HttpToolConfig config) {
        ToolEntry entry = ToolEntry.builder()
                .name(name)
                .description(description)
                .type(ToolType.HTTP)
                .httpConfig(config)
                .requiresAuth(config.getHeaders() != null
                        && config.getHeaders().containsKey("Authorization"))
                .build();
        agentTools.computeIfAbsent(agentId, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public void registerMcpTool(String agentId, McpToolConfig config) {
        ToolEntry entry = ToolEntry.builder()
                .name(config.getName())
                .description(config.getDescription())
                .type(ToolType.MCP)
                .mcpConfig(config)
                .requiresAuth(config.isRequiresAuth())
                .build();
        agentTools.computeIfAbsent(agentId, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public void bindUserCredential(String userId, String toolName, UserToolCredential credential) {
        credentialStore.save(credential);
    }

    @Override
    public void unbindUserCredential(String userId, String toolName) {
        credentialStore.delete(userId, toolName);
    }

    @Override
    public UserToolCredential getUserCredential(String userId, String toolName) {
        return credentialStore.find(userId, toolName);
    }

    @Override
    public List<ToolDefinition> getToolsByAgent(String agentId) {
        List<ToolEntry> tools = agentTools.get(agentId);
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    @Override
    public List<ToolDefinition> getAvailableTools(String agentId, String userId) {
        List<ToolDefinition> result = new ArrayList<>();

        // 添加内置工具（对所有 Agent 可用）
        result.addAll(getBuiltInTools());

        // 添加 Agent 级工具
        List<ToolEntry> tools = agentTools.get(agentId);
        if (tools != null) {
            tools.stream()
                    .filter(t -> !t.isRequiresAuth() || credentialStore.isValid(userId, t.getName()))
                    .map(this::toDefinition)
                    .forEach(result::add);
        }

        return result;
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        String toolName = request.getToolName();

        // 优先查找内置工具
        BuiltInTool builtInTool = builtInTools.get(toolName);
        if (builtInTool != null) {
            try {
                return builtInTool.execute(request);
            } catch (Exception e) {
                log.error("Built-in tool execution failed: {}", toolName, e);
                return ToolExecutionResult.error("EXEC_ERROR", "Execution failed: " + e.getMessage());
            }
        }

        // 查找 Agent 级工具
        String agentId = request.getAgentId();
        String userId = request.getUserId();

        List<ToolEntry> tools = agentTools.get(agentId);
        if (tools == null) {
            return ToolExecutionResult.error("TOOL_NOT_FOUND", "Tool not found: " + toolName);
        }

        ToolEntry tool = tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            return ToolExecutionResult.error("TOOL_NOT_FOUND", "Tool not found: " + toolName);
        }

        // 检查凭证
        if (tool.isRequiresAuth()) {
            UserToolCredential credential = credentialStore.find(userId, toolName);
            if (credential == null || credential.isExpired()) {
                return ToolExecutionResult.error("AUTH_REQUIRED",
                        "Authorization required for tool: " + toolName);
            }
        }

        // MVP阶段：Agent 级工具只返回成功，实际执行逻辑后续实现
        return ToolExecutionResult.success(Map.of("status", "executed", "tool", toolName));
    }

    private ToolDefinition toDefinition(ToolEntry entry) {
        return ToolDefinition.builder()
                .name(entry.getName())
                .description(entry.getDescription())
                .requiresAuth(entry.isRequiresAuth())
                .build();
    }

    // 内部类：工具条目
    @lombok.Data
    @lombok.Builder
    private static class ToolEntry {
        private String name;
        private String description;
        private ToolType type;
        private HttpToolConfig httpConfig;
        private McpToolConfig mcpConfig;
        private boolean requiresAuth;
    }

    private enum ToolType {
        HTTP, MCP
    }
}

package com.foggy.navigator.agent.framework.tool.impl;

import com.foggy.navigator.agent.framework.core.model.HttpToolConfig;
import com.foggy.navigator.agent.framework.core.model.McpToolConfig;
import com.foggy.navigator.agent.framework.tool.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存实现的工具注册表
 */
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private final CredentialStore credentialStore;

    // agentId -> List<ToolEntry>
    private final ConcurrentHashMap<String, List<ToolEntry>> agentTools = new ConcurrentHashMap<>();

    public InMemoryToolRegistry(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
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
        List<ToolEntry> tools = agentTools.get(agentId);
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
                .filter(t -> !t.isRequiresAuth() || credentialStore.isValid(userId, t.getName()))
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        String agentId = request.getAgentId();
        String toolName = request.getToolName();
        String userId = request.getUserId();

        List<ToolEntry> tools = agentTools.get(agentId);
        if (tools == null) {
            return ToolExecutionResult.error("AGENT_NOT_FOUND", "Agent not found: " + agentId);
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

        // MVP阶段：只返回成功，实际执行逻辑后续实现
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

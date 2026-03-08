package com.foggy.navigator.agent.framework.tool.impl;

import com.foggy.navigator.agent.framework.core.model.HttpToolConfig;
import com.foggy.navigator.agent.framework.core.model.McpToolConfig;
import com.foggy.navigator.agent.framework.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryToolRegistryTest {

    private InMemoryToolRegistry registry;
    private InMemoryCredentialStore credentialStore;

    @BeforeEach
    void setUp() {
        credentialStore = new InMemoryCredentialStore();
        registry = new InMemoryToolRegistry(credentialStore);
    }

    @Test
    void registerBuiltInTool_shouldMakeToolAvailableToAllAgents() {
        BuiltInTool testTool = new BuiltInTool() {
            @Override
            public String getName() { return "test-builtin"; }
            @Override
            public String getDescription() { return "Test built-in tool"; }
            @Override
            public Map<String, Object> getParameters() { return Map.of(); }
            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.success("executed");
            }
        };

        registry.registerBuiltInTool(testTool);

        List<ToolDefinition> builtInTools = registry.getBuiltInTools();
        assertEquals(1, builtInTools.size());
        assertEquals("test-builtin", builtInTools.get(0).getName());

        // Built-in tools should be available for any agent
        List<ToolDefinition> availableTools = registry.getAvailableTools("any-agent", "any-user");
        assertTrue(availableTools.stream().anyMatch(t -> t.getName().equals("test-builtin")));
    }

    @Test
    void executeBuiltInTool_shouldWork() {
        BuiltInTool testTool = new BuiltInTool() {
            @Override
            public String getName() { return "echo"; }
            @Override
            public String getDescription() { return "Echo tool"; }
            @Override
            public Map<String, Object> getParameters() { return Map.of(); }
            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.success(request.getParameters().get("message"));
            }
        };
        registry.registerBuiltInTool(testTool);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .toolName("echo")
                .parameters(Map.of("message", "hello"))
                .build();

        ToolExecutionResult result = registry.executeTool(request);
        assertTrue(result.isSuccess());
        assertEquals("hello", result.getData());
    }

    @Test
    void registerHttpTool_shouldAddTool() {
        HttpToolConfig config = HttpToolConfig.builder()
                .url("https://api.example.com")
                .method("GET")
                .build();

        registry.registerHttpTool("agent-1", "search", "Search API", config);

        List<ToolDefinition> tools = registry.getToolsByAgent("agent-1");
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).getName());
        assertEquals("Search API", tools.get(0).getDescription());
    }

    @Test
    void registerHttpTool_shouldDetectAuthRequirement() {
        HttpToolConfig configWithAuth = HttpToolConfig.builder()
                .url("https://api.example.com")
                .method("GET")
                .headers(Map.of("Authorization", "Bearer ${token}"))
                .build();

        registry.registerHttpTool("agent-1", "private-api", "Private API", configWithAuth);

        List<ToolDefinition> tools = registry.getToolsByAgent("agent-1");
        assertTrue(tools.get(0).isRequiresAuth());
    }

    @Test
    void registerMcpTool_shouldAddTool() {
        McpToolConfig config = McpToolConfig.builder()
                .name("github")
                .description("GitHub MCP Tool")
                .mcpServerUrl("mcp://github")
                .requiresAuth(true)
                .build();

        registry.registerMcpTool("agent-1", config);

        List<ToolDefinition> tools = registry.getToolsByAgent("agent-1");
        assertEquals(1, tools.size());
        assertEquals("github", tools.get(0).getName());
        assertTrue(tools.get(0).isRequiresAuth());
    }

    @Test
    void getToolsByAgent_shouldReturnEmptyForUnknownAgent() {
        List<ToolDefinition> tools = registry.getToolsByAgent("unknown");
        assertTrue(tools.isEmpty());
    }

    @Test
    void bindUserCredential_shouldStoreCredential() {
        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("github")
                .accessToken("token123")
                .build();

        registry.bindUserCredential("user-1", "github", credential);

        UserToolCredential found = registry.getUserCredential("user-1", "github");
        assertNotNull(found);
        assertEquals("token123", found.getAccessToken());
    }

    @Test
    void unbindUserCredential_shouldRemoveCredential() {
        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("github")
                .accessToken("token123")
                .build();
        registry.bindUserCredential("user-1", "github", credential);

        registry.unbindUserCredential("user-1", "github");

        assertNull(registry.getUserCredential("user-1", "github"));
    }

    @Test
    void getAvailableTools_shouldFilterByCredentials() {
        // Register tool that requires auth
        McpToolConfig mcpConfig = McpToolConfig.builder()
                .name("private-tool")
                .requiresAuth(true)
                .build();
        registry.registerMcpTool("agent-1", mcpConfig);

        // Register tool that doesn't require auth
        HttpToolConfig httpConfig = HttpToolConfig.builder()
                .url("https://public.api.com")
                .build();
        registry.registerHttpTool("agent-1", "public-tool", "Public API", httpConfig);

        // User without credentials - should only see public tool (no built-in tools registered)
        List<ToolDefinition> availableWithoutCred = registry.getAvailableTools("agent-1", "user-1");
        long agentToolCount = availableWithoutCred.stream()
                .filter(t -> t.getName().equals("public-tool"))
                .count();
        assertEquals(1, agentToolCount);

        // Add valid credential for user
        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("private-tool")
                .accessToken("token")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        registry.bindUserCredential("user-1", "private-tool", credential);

        // User with credentials should see both agent tools
        List<ToolDefinition> availableWithCred = registry.getAvailableTools("agent-1", "user-1");
        assertTrue(availableWithCred.stream().anyMatch(t -> t.getName().equals("public-tool")));
        assertTrue(availableWithCred.stream().anyMatch(t -> t.getName().equals("private-tool")));
    }

    @Test
    void executeTool_shouldReturnErrorForUnknownTool_WhenNoBuiltInOrAgentToolMatches() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .agentId("unknown")
                .toolName("some-tool")
                .userId("user-1")
                .build();

        ToolExecutionResult result = registry.executeTool(request);

        assertFalse(result.isSuccess());
        assertEquals("TOOL_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void executeTool_shouldReturnErrorForUnknownTool() {
        HttpToolConfig config = HttpToolConfig.builder().url("https://api.example.com").build();
        registry.registerHttpTool("agent-1", "tool-a", "Tool A", config);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .agentId("agent-1")
                .toolName("tool-b")
                .userId("user-1")
                .build();

        ToolExecutionResult result = registry.executeTool(request);

        assertFalse(result.isSuccess());
        assertEquals("TOOL_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void executeTool_shouldReturnAuthErrorForMissingCredential() {
        McpToolConfig config = McpToolConfig.builder()
                .name("private-tool")
                .requiresAuth(true)
                .build();
        registry.registerMcpTool("agent-1", config);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .agentId("agent-1")
                .toolName("private-tool")
                .userId("user-1")
                .build();

        ToolExecutionResult result = registry.executeTool(request);

        assertFalse(result.isSuccess());
        assertEquals("AUTH_REQUIRED", result.getErrorCode());
    }

    @Test
    void executeTool_shouldSucceedWithValidCredential() {
        McpToolConfig config = McpToolConfig.builder()
                .name("private-tool")
                .requiresAuth(true)
                .build();
        registry.registerMcpTool("agent-1", config);

        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("private-tool")
                .accessToken("token")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        registry.bindUserCredential("user-1", "private-tool", credential);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .agentId("agent-1")
                .toolName("private-tool")
                .userId("user-1")
                .build();

        ToolExecutionResult result = registry.executeTool(request);

        assertTrue(result.isSuccess());
    }
}

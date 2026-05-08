package com.foggy.navigator.agent.framework.tool;

import java.util.Map;

/**
 * Provider interface for injecting trusted runtime context variables into tool execution.
 * Allows passing secrets (e.g., task-scoped tokens) securely without exposing them to the LLM.
 */
public interface ToolRuntimeContextProvider {

    /**
     * Provides runtime context variables for the given tool execution request.
     *
     * @param request the context request containing session, user, tenant, and tool info
     * @return a map of context variables to inject, or null/empty if none
     */
    Map<String, Object> provide(ToolRuntimeContextRequest request);
}

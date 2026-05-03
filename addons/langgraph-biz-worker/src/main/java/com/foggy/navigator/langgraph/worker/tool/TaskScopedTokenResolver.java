package com.foggy.navigator.langgraph.worker.tool;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextKeys;

import java.util.Map;

/**
 * Utility to extract the task-scoped token from a tool execution request's
 * runtime context.
 *
 * The token must NEVER be read from {@code request.getParameters()} — that map
 * is LLM-controlled and cannot be trusted for authentication.
 *
 * The canonical runtime context key is {@link #TOKEN_KEY}.
 */
public final class TaskScopedTokenResolver {

    /** Runtime context key used to carry the task-scoped token. */
    public static final String TOKEN_KEY = ToolRuntimeContextKeys.TASK_SCOPED_TOKEN;

    private TaskScopedTokenResolver() {
        // utility class
    }

    /**
     * Resolves the task-scoped token from the request's runtime context.
     *
     * @return the token string, or {@code null} if absent or blank
     */
    public static String resolve(ToolExecutionRequest request) {
        if (request == null) return null;
        Map<String, Object> ctx = request.getRuntimeContext();
        if (ctx == null) return null;
        Object value = ctx.get(TOKEN_KEY);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}

package com.foggy.navigator.langgraph.worker.tool;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.langgraph.worker.client.WorkerGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM-callable tool: list business functions visible to the current task scope.
 * Delegates to Java Worker Gateway via HTTP.
 *
 * The task-scoped token is obtained exclusively from the Worker runtime context
 * ({@code request.getRuntimeContext()}). It is NOT a parameter visible to the LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListBusinessFunctionsTool implements BuiltInTool {

    private final WorkerGatewayClient workerGatewayClient;

    @Override
    public String getName() {
        return "list_business_functions";
    }

    @Override
    public String getDescription() {
        return "List business functions available to the current task. "
                + "Returns function summaries filtered by the task's skill/app/user scope. "
                + "Optional filters: domain, risk_level.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("domain", Map.of(
                "type", "string",
                "description", "Optional domain filter (e.g. 'order', 'inventory')"
        ));
        properties.put("risk_level", Map.of(
                "type", "string",
                "description", "Optional risk level filter (e.g. 'LOW', 'HIGH')"
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        // Token comes from runtime context ONLY — never from LLM-controlled parameters
        String token = TaskScopedTokenResolver.resolve(request);
        if (token == null) {
            return ToolExecutionResult.error("MISSING_TOKEN", "task_scoped_token is required (runtime context)");
        }

        Map<String, Object> params = request.getParameters();
        String domain = params != null ? (String) params.get("domain") : null;
        String riskLevel = params != null ? (String) params.get("risk_level") : null;

        try {
            Map<String, Object> result = workerGatewayClient.listBusinessFunctions(token, domain, riskLevel);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("list_business_functions failed", e);
            return ToolExecutionResult.error("GATEWAY_ERROR", "Failed to list business functions: " + e.getMessage());
        }
    }
}

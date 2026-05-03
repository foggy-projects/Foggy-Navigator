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
 * LLM-callable tool: get business function schema (input/output JSON schema, risk level, approval info).
 * Delegates to Java Worker Gateway via HTTP.
 *
 * The task-scoped token is obtained exclusively from the Worker runtime context
 * ({@code request.getRuntimeContext()}). It is NOT a parameter visible to the LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetBusinessFunctionSchemaTool implements BuiltInTool {

    private final WorkerGatewayClient workerGatewayClient;

    @Override
    public String getName() {
        return "get_business_function_schema";
    }

    @Override
    public String getDescription() {
        return "Get the input/output schema, risk level, and approval requirements for a specific business function. "
                + "Use this to understand what parameters a function needs before invoking it.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("function_id", Map.of(
                "type", "string",
                "description", "The ID of the business function"
        ));
        properties.put("version", Map.of(
                "type", "string",
                "description", "The version of the business function"
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{"function_id", "version"});
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
        if (params == null) {
            return ToolExecutionResult.error("MISSING_PARAMS", "Parameters are required");
        }

        String functionId = (String) params.get("function_id");
        if (functionId == null || functionId.isBlank()) {
            return ToolExecutionResult.error("MISSING_FUNCTION_ID", "function_id is required");
        }

        String version = (String) params.get("version");
        if (version == null || version.isBlank()) {
            return ToolExecutionResult.error("MISSING_VERSION", "version is required");
        }

        try {
            Map<String, Object> result = workerGatewayClient.getBusinessFunctionSchema(token, functionId, version);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("get_business_function_schema failed for functionId={}, version={}", functionId, version, e);
            return ToolExecutionResult.error("GATEWAY_ERROR", "Failed to get function schema: " + e.getMessage());
        }
    }
}

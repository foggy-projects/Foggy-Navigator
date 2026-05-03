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
 * Placeholder tool for running business scripts (fsscript).
 * The script runtime is not yet available in this stage.
 * Java side does not execute scripts — this is a Worker-side concern.
 * Reports a SCRIPT_NOT_AVAILABLE tool message to Java for audit (best-effort).
 *
 * The task-scoped token is obtained exclusively from the Worker runtime context
 * ({@code request.getRuntimeContext()}). It is NOT a parameter visible to the LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunBusinessScriptTool implements BuiltInTool {

    private final WorkerGatewayClient workerGatewayClient;

    @Override
    public String getName() {
        return "run_business_script";
    }

    @Override
    public String getDescription() {
        return "Run a business script (fsscript) in a controlled Worker-side runtime. "
                + "Note: The script runtime is not yet available. This tool will return an error "
                + "indicating the runtime is not ready.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("script_id", Map.of(
                "type", "string",
                "description", "The ID of the business script to run"
        ));
        properties.put("input", Map.of(
                "type", "object",
                "description", "Input parameters for the script"
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{"script_id"});
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

        String scriptId = (String) params.get("script_id");
        if (scriptId == null || scriptId.isBlank()) {
            return ToolExecutionResult.error("MISSING_SCRIPT_ID", "script_id is required");
        }

        log.info("run_business_script called for scriptId={}, but runtime is not available", scriptId);

        // Report tool message to Java for audit (best-effort — must not affect the tool result)
        reportToolMessageSafely(token, scriptId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SCRIPT_RUNTIME_NOT_AVAILABLE");
        result.put("script_id", scriptId);
        result.put("message", "The business script runtime is not yet available. "
                + "Script execution will be supported in a future stage. "
                + "Please use invoke_business_function for registered business functions.");

        return ToolExecutionResult.success(result);
    }

    private void reportToolMessageSafely(String token, String scriptId) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("toolName", "run_business_script");
            message.put("status", "SCRIPT_NOT_AVAILABLE");
            message.put("message", "fsscript runtime not yet available; scriptId=" + scriptId);
            workerGatewayClient.reportToolMessage(token, message);
        } catch (Exception e) {
            log.warn("Failed to report tool message for run_business_script scriptId={}: {}", scriptId, e.getMessage());
        }
    }
}

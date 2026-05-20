package com.foggy.navigator.langgraph.worker.tool;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.langgraph.worker.client.WorkerGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM-callable tool: invoke a business function via Java Worker Gateway.
 * Handles SUSPENDED (approval required) and ADAPTER_NOT_IMPLEMENTED statuses.
 * Reports tool execution messages back to Java for audit.
 *
 * The task-scoped token is obtained exclusively from the Worker runtime context
 * ({@code request.getRuntimeContext()}). It is NOT a parameter visible to the LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvokeBusinessFunctionTool implements BuiltInTool {

    private final WorkerGatewayClient workerGatewayClient;

    @Override
    public String getName() {
        return "invoke_business_function";
    }

    @Override
    public String getDescription() {
        return "Invoke a business function through the Java Worker Gateway. "
                + "If the function requires approval, the call will return a suspended status with a suspendId. "
                + "The function will be executed once approval is granted through the Control Plane.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("function_id", Map.of(
                "type", "string",
                "description", "The ID of the business function to invoke"
        ));
        properties.put("version", Map.of(
                "type", "string",
                "description", "The version of the business function"
        ));
        properties.put("input", Map.of(
                "type", "object",
                "description", "The input parameters for the function"
        ));
        properties.put("idempotency_key", Map.of(
                "type", "string",
                "description", "Optional idempotency key to prevent duplicate invocations"
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{"function_id", "version", "input"});
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
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

        Object input = params.get("input");
        if (input == null) {
            return ToolExecutionResult.error("MISSING_INPUT", "input is required");
        }

        String idempotencyKey = (String) params.get("idempotency_key");

        // Build invoke body
        Map<String, Object> invokeBody = new LinkedHashMap<>();
        invokeBody.put("version", version);
        if (input instanceof Map) {
            invokeBody.put("input", input);
        } else if (input instanceof String) {
            invokeBody.put("inputJson", input);
        } else {
            invokeBody.put("input", input);
        }
        if (idempotencyKey != null) {
            invokeBody.put("idempotencyKey", idempotencyKey);
        }

        try {
            Map<String, Object> result = workerGatewayClient.invokeBusinessFunction(token, functionId, invokeBody);
            String status = (String) result.get("status");

            // Build structured response for LLM
            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("function_id", result.get("functionId"));
            toolResult.put("version", result.get("version"));
            toolResult.put("status", status);
            toolResult.put("message", result.get("message"));

            if ("SUSPENDED".equals(status)) {
                toolResult.put("approval_wait", true);
                toolResult.put("suspend_id", result.get("suspendId"));
                log.info("invoke_business_function: function {} suspended, suspendId={}", functionId, result.get("suspendId"));
            } else if ("ADAPTER_NOT_IMPLEMENTED".equals(status)) {
                toolResult.put("approval_wait", false);
                log.info("invoke_business_function: function {} authorized but adapter not implemented", functionId);
            }

            // Report tool message to Java for audit
            reportToolMessageSafely(token, functionId, status, (String) result.get("suspendId"));

            return ToolExecutionResult.success(toolResult);
        } catch (Exception e) {
            log.error("invoke_business_function failed for functionId={}", functionId, e);
            reportToolMessageSafely(token, functionId, "ERROR", null);
            String detail = exceptionDetail(e);
            if (isConfigurationError(detail)) {
                return ToolExecutionResult.builder()
                        .success(false)
                        .errorCode("CONFIGURATION_ERROR")
                        .errorMessage("业务函数配置错误：adapter upstream_ref 不合法或未配置，需检查 ClientApp upstream route / function adapter config。")
                        .data(Map.of(
                                "error_category", "CONFIGURATION",
                                "recoverable", false,
                                "llm_retry_allowed", false,
                                "gateway_error", detail
                        ))
                        .build();
            }
            return ToolExecutionResult.error("GATEWAY_ERROR", "Failed to invoke business function: " + detail);
        }
    }

    private boolean isConfigurationError(String detail) {
        if (detail == null) {
            return false;
        }
        return detail.contains("upstreamRef must match [A-Za-z0-9._-]{1,128}")
                || detail.contains("Unauthorized or unconfigured upstream_ref")
                || detail.contains("Rest adapter requires 'upstream_ref'")
                || detail.contains("Adapter config is missing or blank");
    }

    private String exceptionDetail(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(current.getMessage());
            if (current instanceof WebClientResponseException webException) {
                String responseBody = webException.getResponseBodyAsString();
                if (responseBody != null && !responseBody.isBlank()) {
                    builder.append(" | ").append(responseBody);
                }
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private void reportToolMessageSafely(String token, String functionId, String status, String suspendId) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("toolName", "invoke_business_function");
            message.put("functionId", functionId);
            message.put("status", mapToToolMessageStatus(status));
            if (suspendId != null) {
                message.put("suspendId", suspendId);
            }
            workerGatewayClient.reportToolMessage(token, message);
        } catch (Exception e) {
            // Tool message reporting is best-effort audit; don't fail the tool result
            log.warn("Failed to report tool message for functionId={}: {}", functionId, e.getMessage());
        }
    }

    private String mapToToolMessageStatus(String gatewayStatus) {
        if ("SUSPENDED".equals(gatewayStatus)) return "APPROVAL_WAIT";
        if ("ADAPTER_NOT_IMPLEMENTED".equals(gatewayStatus)) return "SUCCESS";
        if ("ERROR".equals(gatewayStatus)) return "ERROR";
        return gatewayStatus;
    }
}

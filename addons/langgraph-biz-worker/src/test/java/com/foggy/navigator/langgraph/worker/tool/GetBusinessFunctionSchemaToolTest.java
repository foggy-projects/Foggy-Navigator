package com.foggy.navigator.langgraph.worker.tool;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.langgraph.worker.client.WorkerGatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetBusinessFunctionSchemaToolTest {

    @Mock
    private WorkerGatewayClient workerGatewayClient;

    @InjectMocks
    private GetBusinessFunctionSchemaTool tool;

    // ===== Schema contract tests =====

    @Test
    void schema_doesNotContain_taskScopedToken() {
        Map<String, Object> schema = tool.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertFalse(properties.containsKey("task_scoped_token"),
                "task_scoped_token must not appear in LLM-visible tool schema");
    }

    @Test
    void schema_required_doesNotContain_taskScopedToken() {
        Map<String, Object> schema = tool.getParameters();
        Object required = schema.get("required");
        assertNotNull(required);
        List<String> requiredList = Arrays.asList((String[]) required);
        assertFalse(requiredList.contains("task_scoped_token"),
                "task_scoped_token must not appear in required field list");
        assertTrue(requiredList.containsAll(List.of("function_id", "version")),
                "function_id and version must remain required");
    }

    // ===== Success path: token from runtimeContext =====

    @Test
    void execute_success_tokenFromRuntimeContext() {
        Map<String, Object> gatewayResponse = Map.of(
                "functionId", "f1",
                "version", "v1",
                "inputSchemaJson", "{\"type\":\"object\"}",
                "riskLevel", "HIGH",
                "approvalRequired", true
        );
        when(workerGatewayClient.getBusinessFunctionSchema("rt_token", "f1", "v1"))
                .thenReturn(gatewayResponse);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        verify(workerGatewayClient).getBusinessFunctionSchema("rt_token", "f1", "v1");
    }

    // ===== Fail-closed: missing runtime token =====

    @Test
    void execute_failsOnMissingRuntimeToken() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1"))
                // runtimeContext absent
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_TOKEN", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }

    // ===== Security: LLM-supplied token in parameters is ignored =====

    @Test
    void execute_ignores_llmSuppliedTokenInParameters() {
        Map<String, Object> gatewayResponse = Map.of("functionId", "f1", "version", "v1");
        when(workerGatewayClient.getBusinessFunctionSchema("rt_token", "f1", "v1"))
                .thenReturn(gatewayResponse);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of(
                        "task_scoped_token", "malicious_llm_token",  // must be ignored
                        "function_id", "f1",
                        "version", "v1"
                ))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        // Gateway receives the runtime token, not the malicious param
        verify(workerGatewayClient).getBusinessFunctionSchema("rt_token", "f1", "v1");
        verify(workerGatewayClient, never()).getBusinessFunctionSchema(eq("malicious_llm_token"), any(), any());
    }

    // ===== Parameter validation =====

    @Test
    void execute_failsOnMissingFunctionId() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("version", "v1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_FUNCTION_ID", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }

    @Test
    void execute_failsOnMissingVersion() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_VERSION", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }
}

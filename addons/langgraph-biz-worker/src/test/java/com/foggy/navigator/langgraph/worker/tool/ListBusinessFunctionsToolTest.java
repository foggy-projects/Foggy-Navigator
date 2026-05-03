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
class ListBusinessFunctionsToolTest {

    @Mock
    private WorkerGatewayClient workerGatewayClient;

    @InjectMocks
    private ListBusinessFunctionsTool tool;

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
    }

    // ===== Success path: token from runtimeContext =====

    @Test
    void execute_success_tokenFromRuntimeContext() {
        Map<String, Object> gatewayResponse = Map.of(
                "functions", List.of(
                        Map.of("functionId", "f1", "name", "Close Order", "domain", "order")
                )
        );
        when(workerGatewayClient.listBusinessFunctions("rt_token", "order", null))
                .thenReturn(gatewayResponse);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("domain", "order"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        verify(workerGatewayClient).listBusinessFunctions("rt_token", "order", null);
    }

    // ===== Fail-closed: missing runtime token =====

    @Test
    void execute_failsOnMissingRuntimeToken() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("domain", "order"))
                // runtimeContext is absent
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_TOKEN", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }

    @Test
    void execute_failsOnNullRuntimeContext() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .runtimeContext(null)
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_TOKEN", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }

    // ===== Security: LLM-supplied token in parameters is ignored =====

    @Test
    void execute_ignores_llmSuppliedTokenInParameters() {
        Map<String, Object> gatewayResponse = Map.of("functions", List.of());
        // Gateway is called with the RUNTIME token, not the malicious param token
        when(workerGatewayClient.listBusinessFunctions("rt_token", null, null))
                .thenReturn(gatewayResponse);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                // Attacker places a token in parameters (LLM-controlled)
                .parameters(Map.of("task_scoped_token", "malicious_llm_token"))
                // Framework injects the real token via runtimeContext
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        // Gateway must receive the runtime token, NOT the malicious parameter token
        verify(workerGatewayClient).listBusinessFunctions("rt_token", null, null);
        verify(workerGatewayClient, never()).listBusinessFunctions(eq("malicious_llm_token"), any(), any());
    }

    // ===== Gateway error =====

    @Test
    void execute_failsOnGatewayError() {
        when(workerGatewayClient.listBusinessFunctions("rt_token", null, null))
                .thenThrow(new RuntimeException("Gateway unavailable"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("GATEWAY_ERROR", result.getErrorCode());
    }
}

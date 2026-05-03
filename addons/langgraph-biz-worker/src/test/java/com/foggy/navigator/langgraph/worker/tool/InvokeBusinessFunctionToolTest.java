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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvokeBusinessFunctionToolTest {

    @Mock
    private WorkerGatewayClient workerGatewayClient;

    @InjectMocks
    private InvokeBusinessFunctionTool tool;

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
        assertTrue(requiredList.containsAll(List.of("function_id", "version", "input")),
                "function_id, version, input must remain required");
    }

    // ===== Success path: SUSPENDED — token from runtimeContext =====

    @Test
    void execute_suspended_tokenFromRuntimeContext() {
        Map<String, Object> gatewayResponse = new HashMap<>();
        gatewayResponse.put("functionId", "f1");
        gatewayResponse.put("version", "v1");
        gatewayResponse.put("status", "SUSPENDED");
        gatewayResponse.put("approvalRequired", true);
        gatewayResponse.put("suspendId", "sus_123");
        gatewayResponse.put("message", "Approval required");

        when(workerGatewayClient.invokeBusinessFunction(eq("rt_token"), eq("f1"), any()))
                .thenReturn(gatewayResponse);
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1", "input", Map.of("key", "value")))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("SUSPENDED", data.get("status"));
        assertEquals(true, data.get("approval_wait"));
        assertEquals("sus_123", data.get("suspend_id"));

        verify(workerGatewayClient).invokeBusinessFunction(eq("rt_token"), eq("f1"), any());
        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), any());
    }

    @Test
    void execute_adapterNotImplemented_tokenFromRuntimeContext() {
        Map<String, Object> gatewayResponse = new HashMap<>();
        gatewayResponse.put("functionId", "f1");
        gatewayResponse.put("version", "v1");
        gatewayResponse.put("status", "ADAPTER_NOT_IMPLEMENTED");
        gatewayResponse.put("approvalRequired", false);
        gatewayResponse.put("message", "Authorized, adapter not implemented");

        when(workerGatewayClient.invokeBusinessFunction(eq("rt_token"), eq("f1"), any()))
                .thenReturn(gatewayResponse);
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1", "input", Map.of("key", "value")))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("ADAPTER_NOT_IMPLEMENTED", data.get("status"));
        assertEquals(false, data.get("approval_wait"));
    }

    // ===== Fail-closed: missing runtime token =====

    @Test
    void execute_failsOnMissingRuntimeToken() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1", "input", Map.of("key", "value")))
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
        Map<String, Object> gatewayResponse = new HashMap<>();
        gatewayResponse.put("functionId", "f1");
        gatewayResponse.put("version", "v1");
        gatewayResponse.put("status", "ADAPTER_NOT_IMPLEMENTED");
        gatewayResponse.put("message", "ok");

        when(workerGatewayClient.invokeBusinessFunction(eq("rt_token"), eq("f1"), any()))
                .thenReturn(gatewayResponse);
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of(
                        "task_scoped_token", "malicious_llm_token",  // must be ignored
                        "function_id", "f1",
                        "version", "v1",
                        "input", Map.of("key", "value")
                ))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        // Gateway called with RUNTIME token only
        verify(workerGatewayClient).invokeBusinessFunction(eq("rt_token"), eq("f1"), any());
        verify(workerGatewayClient, never()).invokeBusinessFunction(eq("malicious_llm_token"), any(), any());
        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), any());
        verify(workerGatewayClient, never()).reportToolMessage(eq("malicious_llm_token"), any());
    }

    // ===== Parameter validation =====

    @Test
    void execute_failsOnMissingInput() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_INPUT", result.getErrorCode());
    }

    // ===== Tool message reporting =====

    @Test
    void execute_reportsToolMessage_onSuccess() {
        Map<String, Object> gatewayResponse = new HashMap<>();
        gatewayResponse.put("functionId", "f1");
        gatewayResponse.put("version", "v1");
        gatewayResponse.put("status", "SUSPENDED");
        gatewayResponse.put("suspendId", "sus_456");
        gatewayResponse.put("message", "Approval required");

        when(workerGatewayClient.invokeBusinessFunction(eq("rt_token"), eq("f1"), any()))
                .thenReturn(gatewayResponse);
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1", "input", Map.of("key", "value")))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        tool.execute(request);

        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), argThat(body -> {
            assertEquals("invoke_business_function", body.get("toolName"));
            assertEquals("f1", body.get("functionId"));
            assertEquals("APPROVAL_WAIT", body.get("status"));
            assertEquals("sus_456", body.get("suspendId"));
            return true;
        }));
    }

    @Test
    void execute_gatewayError_reportsErrorMessage() {
        when(workerGatewayClient.invokeBusinessFunction(eq("rt_token"), eq("f1"), any()))
                .thenThrow(new RuntimeException("Gateway down"));
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("function_id", "f1", "version", "v1", "input", Map.of("key", "value")))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("GATEWAY_ERROR", result.getErrorCode());

        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), argThat(body -> {
            assertEquals("ERROR", body.get("status"));
            return true;
        }));
    }
}

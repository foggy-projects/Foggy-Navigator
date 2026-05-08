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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunBusinessScriptToolTest {

    @Mock
    private WorkerGatewayClient workerGatewayClient;

    @InjectMocks
    private RunBusinessScriptTool tool;

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
        assertTrue(requiredList.contains("script_id"), "script_id must remain required");
    }

    // ===== Success path: token from runtimeContext =====

    @Test
    void execute_returnsNotAvailable_andReportsToolMessage_tokenFromRuntimeContext() {
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("script_id", "script1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("SCRIPT_RUNTIME_NOT_AVAILABLE", data.get("status"));
        assertEquals("script1", data.get("script_id"));

        // Verify Java was notified via runtime token
        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), argThat(body -> {
            assertEquals("run_business_script", body.get("toolName"));
            assertEquals("SCRIPT_NOT_AVAILABLE", body.get("status"));
            return true;
        }));
    }

    // ===== Best-effort: reportToolMessage failure does not affect result =====

    @Test
    void execute_reportToolMessageThrows_stillReturnsNotAvailable() {
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenThrow(new RuntimeException("Gateway down"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("script_id", "script1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        // Should NOT throw; best-effort reporting must not propagate
        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("SCRIPT_RUNTIME_NOT_AVAILABLE", data.get("status"));
    }

    // ===== Fail-closed: missing runtime token =====

    @Test
    void execute_failsOnMissingRuntimeToken_doesNotCallGateway() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("script_id", "script1"))
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
        when(workerGatewayClient.reportToolMessage(eq("rt_token"), any()))
                .thenReturn(Map.of("accepted", true));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                // Attacker places a token in parameters
                .parameters(Map.of("task_scoped_token", "malicious_llm_token", "script_id", "script1"))
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertTrue(result.isSuccess());
        // reportToolMessage called with runtime token, not malicious param
        verify(workerGatewayClient).reportToolMessage(eq("rt_token"), any());
        verify(workerGatewayClient, never()).reportToolMessage(eq("malicious_llm_token"), any());
    }

    // ===== Parameter validation =====

    @Test
    void execute_failsOnMissingScriptId_doesNotCallGateway() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_SCRIPT_ID", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }

    @Test
    void execute_failsOnNullParams_doesNotCallGateway() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .runtimeContext(Map.of(TaskScopedTokenResolver.TOKEN_KEY, "rt_token"))
                .build();

        ToolExecutionResult result = tool.execute(request);

        assertFalse(result.isSuccess());
        assertEquals("MISSING_PARAMS", result.getErrorCode());
        verifyNoInteractions(workerGatewayClient);
    }
}

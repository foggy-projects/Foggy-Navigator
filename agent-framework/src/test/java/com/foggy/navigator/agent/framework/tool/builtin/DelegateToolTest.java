package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DelegateTool 单元测试 — L1
 */
class DelegateToolTest {

    private final DelegateTool delegateTool = new DelegateTool();

    @Test
    void name() {
        assertEquals("delegate", delegateTool.getName());
    }

    @Test
    void parameters_containsRequired() {
        Map<String, Object> schema = delegateTool.getParameters();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("targetAgentId"));
        assertTrue(props.containsKey("intent"));
        assertTrue(props.containsKey("context"));
        assertTrue(props.containsKey("background"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_returnsDelegationMarker() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of(
                        "targetAgentId", "coding-agent",
                        "intent", "Create a REST API"
                ))
                .build();
        ToolExecutionResult result = delegateTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(true, data.get("delegationRequired"));
        assertEquals("coding-agent", data.get("targetAgentId"));
        assertEquals("Create a REST API", data.get("intent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withContext() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of(
                        "targetAgentId", "data-agent",
                        "intent", "Analyze dataset",
                        "context", "{\"datasetId\": \"ds-123\"}"
                ))
                .build();
        ToolExecutionResult result = delegateTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("{\"datasetId\": \"ds-123\"}", data.get("context"));
    }
}

package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.task.AgentTaskManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckAgentTasksToolTest {

    @Test
    void metadata() {
        CheckAgentTasksTool tool = new CheckAgentTasksTool(null);
        assertEquals("check_agent_tasks", tool.getName());
        assertNotNull(tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    @Test
    void execute_withTasks_returnsFormattedReport() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        when(manager.listTasksBySession("session-1")).thenReturn(List.of(
                taskMap("task-1", "CLAUDE_WORKER", "claude-worker", "COMPLETED", "Build succeeded"),
                taskMap("task-2", "CODING", "coding-agent", "RUNNING", null)
        ));

        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("session-1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        String data = result.getData().toString();
        assertTrue(data.contains("2"));
        assertTrue(data.contains("CLAUDE_WORKER"));
        assertTrue(data.contains("claude-worker"));
        assertTrue(data.contains("COMPLETED"));
        assertTrue(data.contains("Build succeeded"));
        assertTrue(data.contains("CODING"));
        assertTrue(data.contains("RUNNING"));
    }

    @Test
    void execute_noTasks_returnsEmptyMessage() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        when(manager.listTasksBySession("session-1")).thenReturn(List.of());

        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("session-1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().toString().contains("没有委派的任务"));
    }

    @Test
    void execute_managerNull_returnsError() {
        CheckAgentTasksTool tool = new CheckAgentTasksTool(null);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("session-1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未启用"));
    }

    @Test
    void execute_noSessionId_returnsError() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("会话 ID"));
    }

    @Test
    void execute_blankSessionId_returnsError() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("  ")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
    }

    @Test
    void execute_longResultSummary_truncated() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        String longSummary = "A".repeat(300);
        when(manager.listTasksBySession("s1")).thenReturn(List.of(
                taskMap("t1", "DELEGATION", "target", "COMPLETED", longSummary)
        ));

        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("s1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertTrue(result.isSuccess());
        String data = result.getData().toString();
        // Result summary is truncated to 200 chars + "..."
        assertTrue(data.contains("..."));
    }

    @Test
    void execute_exceptionFromManager_returnsError() {
        AgentTaskManager manager = mock(AgentTaskManager.class);
        when(manager.listTasksBySession("s1")).thenThrow(new RuntimeException("DB down"));

        CheckAgentTasksTool tool = new CheckAgentTasksTool(manager);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .sessionId("s1")
                .parameters(Map.of())
                .build();

        ToolExecutionResult result = tool.execute(request);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("DB down"));
    }

    private Map<String, Object> taskMap(String taskId, String taskType, String targetAgentId,
                                         String status, String resultSummary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("parentSessionId", "parent-1");
        map.put("taskType", taskType);
        map.put("targetAgentId", targetAgentId);
        map.put("status", status);
        map.put("resultSummary", resultSummary);
        map.put("externalTaskId", "ext-" + taskId);
        map.put("createdAt", LocalDateTime.now());
        map.put("completedAt", "COMPLETED".equals(status) ? LocalDateTime.now() : null);
        return map;
    }
}

package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.AgentTaskEntity;
import com.foggy.navigator.session.repository.AgentTaskRepository;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskControllerTest {

    private AgentTaskRepository repository;
    private AgentTaskController controller;

    @BeforeEach
    void setUp() {
        repository = mock(AgentTaskRepository.class);
        controller = new AgentTaskController(repository);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .username("testuser")
                .tenantId("tenant-1")
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listTasks_returnsSortedByCreatedAtDesc() {
        AgentTaskEntity task1 = createEntity("task-1", "CODING", "COMPLETED",
                LocalDateTime.of(2026, 2, 10, 10, 0));
        AgentTaskEntity task2 = createEntity("task-2", "CLAUDE_WORKER", "RUNNING",
                LocalDateTime.of(2026, 2, 11, 10, 0));

        when(repository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(task2, task1));

        RX<List<Map<String, Object>>> result = controller.listTasks();

        assertEquals(200, result.getCode());
        List<Map<String, Object>> data = result.getData();
        assertEquals(2, data.size());
        assertEquals("task-2", data.get(0).get("taskId"));
        assertEquals("task-1", data.get(1).get("taskId"));
    }

    @Test
    void listTasks_emptyList() {
        when(repository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of());

        RX<List<Map<String, Object>>> result = controller.listTasks();

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void listTasksBySession_returnsSessionTasks() {
        AgentTaskEntity task = createEntity("task-1", "DELEGATION", "PENDING",
                LocalDateTime.now());
        when(repository.findByParentSessionId("session-abc"))
                .thenReturn(List.of(task));

        RX<List<Map<String, Object>>> result = controller.listTasksBySession("session-abc");

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        Map<String, Object> map = result.getData().get(0);
        assertEquals("task-1", map.get("taskId"));
        assertEquals("DELEGATION", map.get("taskType"));
        assertEquals("user-1", map.get("userId"));
        assertEquals("Test prompt", map.get("prompt"));
    }

    @Test
    void listTasks_mapContainsAllFields() {
        AgentTaskEntity task = createEntity("task-1", "CODING", "COMPLETED",
                LocalDateTime.now());
        task.setResultSummary("All good");
        task.setCompletedAt(LocalDateTime.now());
        task.setExternalTaskId("ext-123");

        when(repository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(task));

        RX<List<Map<String, Object>>> result = controller.listTasks();
        Map<String, Object> map = result.getData().get(0);

        assertEquals("task-1", map.get("taskId"));
        assertEquals("parent-1", map.get("parentSessionId"));
        assertEquals("user-1", map.get("userId"));
        assertEquals("tutor-agent", map.get("sourceAgentId"));
        assertEquals("coding-agent", map.get("targetAgentId"));
        assertEquals("CODING", map.get("taskType"));
        assertEquals("COMPLETED", map.get("status"));
        assertEquals("Test prompt", map.get("prompt"));
        assertEquals("All good", map.get("resultSummary"));
        assertEquals("ext-123", map.get("externalTaskId"));
        assertNotNull(map.get("createdAt"));
        assertNotNull(map.get("completedAt"));
    }

    private AgentTaskEntity createEntity(String taskId, String taskType, String status,
                                          LocalDateTime createdAt) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId(taskId);
        entity.setParentSessionId("parent-1");
        entity.setUserId("user-1");
        entity.setSourceAgentId("tutor-agent");
        entity.setTargetAgentId("coding-agent");
        entity.setTaskType(taskType);
        entity.setStatus(status);
        entity.setPrompt("Test prompt");
        entity.setCreatedAt(createdAt);
        return entity;
    }
}

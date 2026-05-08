package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.session.service.TaskDispatchRequest;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(taskDispatchFacade);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createTask_success() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hello")
                .build();

        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .providerType("claude-worker")
                .build();

        when(taskDispatchFacade.createTask(eq(request), any(AgentResolveContext.class))).thenReturn(dto);

        RX<DispatchTaskDTO> result = controller.createTask(request);

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getTaskId());
    }

    @Test
    void getTask_found() {
        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-1")
                .status("RUNNING")
                .build();

        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(dto));

        RX<DispatchTaskDTO> result = controller.getTask("task-1");

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getTaskId());
    }

    @Test
    void getTask_notFound() {
        when(taskDispatchFacade.getTask(eq("task-999"), any(AgentResolveContext.class)))
                .thenReturn(Optional.empty());

        RX<DispatchTaskDTO> result = controller.getTask("task-999");

        assertNull(result.getData());
    }

    @Test
    void listTasks_withSessionId() {
        List<DispatchTaskDTO> tasks = List.of(
                DispatchTaskDTO.builder().taskId("task-1").build()
        );
        when(taskDispatchFacade.listTasksBySession("session-1")).thenReturn(tasks);

        RX<List<DispatchTaskDTO>> result = controller.listTasks("session-1");

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        verify(taskDispatchFacade).listTasksBySession("session-1");
        verify(taskDispatchFacade, never()).listActiveTasks(anyString());
    }

    @Test
    void listTasks_withoutSessionId_returnsActive() {
        List<DispatchTaskDTO> tasks = List.of(
                DispatchTaskDTO.builder().taskId("task-active-1").build()
        );
        when(taskDispatchFacade.listActiveTasks(USER_ID)).thenReturn(tasks);

        RX<List<DispatchTaskDTO>> result = controller.listTasks(null);

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals("task-active-1", result.getData().get(0).getTaskId());
        verify(taskDispatchFacade).listActiveTasks(USER_ID);
        verify(taskDispatchFacade, never()).listTasksBySession(anyString());
    }

    @Test
    void cancelTask_success() {
        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .status("RUNNING")
                .build();
        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(dto));

        RX<String> result = controller.cancelTask("task-1", null);

        assertNotNull(result.getData());
        verify(taskDispatchFacade).cancelTask(eq("task-1"), eq("agent-1"), any(AgentResolveContext.class));
    }

    @Test
    void cancelTask_deadlockFallback_terminalState() {
        // First getTask call (before cancel) returns RUNNING
        DispatchTaskDTO running = DispatchTaskDTO.builder()
                .taskId("task-1").agentId("agent-1").status("RUNNING").build();
        // Second getTask call (after deadlock) returns ABORTED (reactor thread won the race)
        DispatchTaskDTO aborted = DispatchTaskDTO.builder()
                .taskId("task-1").agentId("agent-1").status("ABORTED").build();
        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(aborted));
        doThrow(new org.springframework.dao.PessimisticLockingFailureException("Deadlock"))
                .when(taskDispatchFacade).cancelTask(eq("task-1"), eq("agent-1"), any(AgentResolveContext.class));

        RX<String> result = controller.cancelTask("task-1", null);

        // Should return success (idempotent) since task is already in terminal state
        assertNotNull(result.getData());
        assertTrue(result.getData().contains("ABORTED"));
    }

    @Test
    void cancelTask_deadlockFallback_nonTerminal() {
        DispatchTaskDTO running = DispatchTaskDTO.builder()
                .taskId("task-1").agentId("agent-1").status("RUNNING").build();
        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(running));
        doThrow(new org.springframework.dao.PessimisticLockingFailureException("Deadlock"))
                .when(taskDispatchFacade).cancelTask(eq("task-1"), eq("agent-1"), any(AgentResolveContext.class));

        RX<String> result = controller.cancelTask("task-1", null);

        // Should return failure (task still not terminal after deadlock)
        assertNull(result.getData());
    }

    @Test
    void respondToTask_success() {
        Map<String, Object> body = Map.of("decision", "approve");

        RX<String> result = controller.respondToTask("task-1", body);

        assertNotNull(result.getData());
        verify(taskDispatchFacade).respondToTask("task-1", USER_ID, body);
    }

    @Test
    void resumeTask_success() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .build();

        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .providerType("claude-worker")
                .build();

        when(taskDispatchFacade.resumeTask(eq(request), any(AgentResolveContext.class))).thenReturn(dto);

        RX<DispatchTaskDTO> result = controller.resumeTask(request);

        assertNotNull(result.getData());
        assertEquals("task-resumed", result.getData().getTaskId());
    }

    @Test
    void resumeTask_providerNotFound_returnsFailA() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-unknown")
                .directoryId("dir-unknown")
                .prompt("continue")
                .build();

        when(taskDispatchFacade.resumeTask(eq(request), any(AgentResolveContext.class)))
                .thenThrow(new IllegalArgumentException("No provider found for resume request"));

        RX<DispatchTaskDTO> result = controller.resumeTask(request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("No provider found"));
    }

    @Test
    void respondToTask_unsupported() {
        Map<String, Object> body = Map.of("decision", "approve");
        doThrow(new UnsupportedOperationException("respond not supported by codex-worker"))
                .when(taskDispatchFacade).respondToTask("task-1", USER_ID, body);

        RX<String> result = controller.respondToTask("task-1", body);

        assertNull(result.getData());
    }

    @Test
    void listWorkerSessions_delegatesWithCurrentUser() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("sessionId", "worker-session-1", "source", "session-store")
        );
        when(taskDispatchFacade.listWorkerSessions("lg-worker-1", USER_ID)).thenReturn(sessions);

        RX<List<Map<String, Object>>> result = controller.listWorkerSessions("lg-worker-1");

        assertEquals(sessions, result.getData());
        verify(taskDispatchFacade).listWorkerSessions("lg-worker-1", USER_ID);
    }

    @Test
    void getWorkerSessionMessageCount_delegatesWithCurrentUser() {
        Map<String, Object> count = Map.of("total", 2, "user_count", 1, "assistant_count", 1);
        when(taskDispatchFacade.getWorkerSessionMessageCount("lg-worker-1", "worker-session-1", USER_ID))
                .thenReturn(count);

        RX<Map<String, Object>> result =
                controller.getWorkerSessionMessageCount("lg-worker-1", "worker-session-1");

        assertEquals(count, result.getData());
        verify(taskDispatchFacade).getWorkerSessionMessageCount("lg-worker-1", "worker-session-1", USER_ID);
    }

    @Test
    void getWorkerSessionMessages_delegatesWithCurrentUserAndPaging() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", "ok", "taskId", "lgt-task-1")
        );
        when(taskDispatchFacade.getWorkerSessionMessages("lg-worker-1", "worker-session-1", USER_ID, 10, 20))
                .thenReturn(messages);

        RX<List<Map<String, Object>>> result =
                controller.getWorkerSessionMessages("lg-worker-1", "worker-session-1", 10, 20);

        assertEquals(messages, result.getData());
        verify(taskDispatchFacade).getWorkerSessionMessages("lg-worker-1", "worker-session-1", USER_ID, 10, 20);
    }

    @Test
    void syncWorkerSessions_delegatesWithCurrentUserAndTenant() {
        Map<String, Object> syncResult = Map.of("synced", 0, "total", 1, "source", "session-store");
        when(taskDispatchFacade.syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID)).thenReturn(syncResult);

        RX<Map<String, Object>> result = controller.syncWorkerSessions("lg-worker-1");

        assertEquals(syncResult, result.getData());
        verify(taskDispatchFacade).syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID);
    }

    @Test
    void syncWorkerSessions_failureReturnsFailB() {
        when(taskDispatchFacade.syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID))
                .thenThrow(new IllegalArgumentException("Worker not found"));

        RX<Map<String, Object>> result = controller.syncWorkerSessions("lg-worker-1");

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("Worker not found"));
    }
}

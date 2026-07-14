package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeWorkerSessionQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "claude-session-1";

    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient client;
    private ClaudeWorkerSessionQueryService service;

    @BeforeEach
    void setUp() {
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        client = mock(ClaudeWorkerClient.class);
        service = new ClaudeWorkerSessionQueryService(workerService, taskService);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
    }

    @Test
    void declares_worker_session_capabilities_only() {
        assertInstanceOf(WorkerSessionQueryProvider.class, service);
        assertFalse(service instanceof TaskLookupProvider);
        assertFalse(service instanceof TaskCommandProvider);
        assertFalse(service instanceof TaskListingProvider);

        assertEquals("claude-worker", service.getProviderType());
        assertTrue(service.supports(TaskQueryCapability.LIST_WORKER_SESSIONS));
        assertTrue(service.supports(TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT));
        assertTrue(service.supports(TaskQueryCapability.GET_WORKER_SESSION_MESSAGES));
        assertTrue(service.supports(TaskQueryCapability.SYNC_WORKER_SESSIONS));
        assertFalse(service.supports(TaskQueryCapability.CREATE_TASK_DIRECT));
    }

    @Test
    void lists_worker_sessions_from_worker_client() {
        when(client.listSessions()).thenReturn(Mono.just(List.of(Map.of(
                "session_id", SESSION_ID,
                "worker_id", WORKER_ID,
                "project", "/home/sa/workspace/app",
                "model", "claude-opus",
                "status", "COMPLETED",
                "latest_task_id", "ctask-1",
                "slug", "ignored",
                "prompt", "fix bug",
                "created_at", LocalDateTime.of(2026, 6, 1, 10, 0),
                "updated_at", LocalDateTime.of(2026, 6, 1, 10, 1)
        ))));

        List<WorkerSessionSummary> result = service.listWorkerSessionSummaries(WORKER_ID, USER_ID);

        assertEquals(1, result.size());
        assertEquals(SESSION_ID, result.get(0).sessionId());
        assertEquals(WORKER_ID, result.get(0).workerId());
        assertEquals("ctask-1", result.get(0).latestTaskId());
    }

    @Test
    void counts_worker_session_messages_from_worker_client() {
        when(client.getSessionMessageCount(SESSION_ID)).thenReturn(Mono.just(Map.of(
                "user_count", 2,
                "assistant_count", 3,
                "total", 5
        )));

        WorkerSessionMessageCount result =
                service.getWorkerSessionMessageCountResult(WORKER_ID, SESSION_ID, USER_ID);

        assertEquals(2L, result.userCount());
        assertEquals(3L, result.assistantCount());
        assertEquals(5L, result.total());
    }

    @Test
    void returns_paged_worker_session_messages_from_worker_client() {
        when(client.getSessionMessages(SESSION_ID, 1, 2)).thenReturn(Mono.just(List.of(
                Map.of("role", "assistant", "content", "second", "taskId", "ctask-1"),
                Map.of("role", "user", "content", "third", "taskId", "ctask-2")
        )));

        List<WorkerSessionMessage> result =
                service.listWorkerSessionMessages(WORKER_ID, SESSION_ID, USER_ID, 1, 2);

        assertEquals(2, result.size());
        assertEquals("assistant", result.get(0).role());
        assertEquals("second", result.get(0).content());
        assertEquals("ctask-2", result.get(1).taskId());
    }

    @Test
    void sync_worker_sessions_delegates_local_projection_to_task_service() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-1", "cwd", "/home/sa/workspace/a"),
                Map.of("session_id", "sess-2", "cwd", "/home/sa/workspace/b")
        );
        when(client.syncSessions()).thenReturn(Mono.just(Map.of("status", "ok")));
        when(client.listSessions()).thenReturn(Mono.just(sessions));
        when(taskService.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions)).thenReturn(1);

        WorkerSessionSyncResult result = service.syncWorkerSessionState(WORKER_ID, USER_ID, TENANT_ID);

        assertEquals(1L, result.synced());
        assertEquals(2L, result.total());
        verify(taskService).syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);
    }

    @Test
    void rejects_worker_owned_by_other_user() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId("user-2");
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.listWorkerSessionSummaries(WORKER_ID, USER_ID));

        assertEquals("Worker not found", error.getMessage());
        verify(workerService, never()).createClient(any());
    }
}

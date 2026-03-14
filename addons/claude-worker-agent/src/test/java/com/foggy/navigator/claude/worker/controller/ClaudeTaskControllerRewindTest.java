package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.ConversationConfigService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClaudeTaskController.rewindToCheckpoint — both conversation_fork and file_rewind modes.
 */
class ClaudeTaskControllerRewindTest {

    private ClaudeTaskController controller;
    private ClaudeTaskService taskService;
    private ClaudeWorkerService workerService;
    private ConversationConfigService conversationConfigService;
    private WorkerStreamRelay streamRelay;

    private static final String USER_ID = "user-rw-001";
    private static final String TENANT_ID = "tenant-rw-001";
    private static final String TASK_ID = "task-rw-001";
    private static final String SESSION_ID = "session-rw-001";
    private static final String CLAUDE_SESSION_ID = "claude-session-rw-001";
    private static final String WORKER_ID = "worker-rw-001";

    @BeforeEach
    void setUp() {
        taskService = mock(ClaudeTaskService.class);
        workerService = mock(ClaudeWorkerService.class);
        conversationConfigService = mock(ConversationConfigService.class);
        streamRelay = mock(WorkerStreamRelay.class);

        controller = new ClaudeTaskController(
                taskService,
                workerService,
                conversationConfigService,
                streamRelay
        );

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // -----------------------------------------------------------------------
    // conversation_fork mode
    // -----------------------------------------------------------------------

    @Test
    void testRewind_conversationFork_success() {
        ClaudeTaskEntity task = createCompletedTask();
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);

        Map<String, Object> rewindResult = Map.of(
                "status", "rewound",
                "user_prompt", "Generate a file"
        );
        when(client.rewindConversation(CLAUDE_SESSION_ID, 2))
                .thenReturn(reactor.core.publisher.Mono.just(rewindResult));

        when(taskService.truncateSessionMessages(SESSION_ID, 2)).thenReturn(5);

        Map<String, Object> body = Map.of(
                "mode", "conversation_fork",
                "turnIndex", 2
        );

        RX<Map<String, Object>> rx = controller.rewindToCheckpoint(TASK_ID, body);

        assertEquals(200, rx.getCode());
        Map<String, Object> data = rx.getData();
        assertEquals("rewound", data.get("status"));
        assertEquals("Generate a file", data.get("userPrompt"));
        assertEquals(TASK_ID, data.get("taskId"));

        verify(client).rewindConversation(CLAUDE_SESSION_ID, 2);
        verify(taskService).truncateSessionMessages(SESSION_ID, 2);
    }

    @Test
    void testRewind_conversationFork_workerError() {
        ClaudeTaskEntity task = createCompletedTask();
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);

        Map<String, Object> errorResult = Map.of(
                "status", "error",
                "message", "Turn 99 not found"
        );
        when(client.rewindConversation(CLAUDE_SESSION_ID, 99))
                .thenReturn(reactor.core.publisher.Mono.just(errorResult));

        Map<String, Object> body = Map.of(
                "mode", "conversation_fork",
                "turnIndex", 99
        );

        // Should throw because rewind returned "error" status
        assertThrows(RuntimeException.class, () ->
                controller.rewindToCheckpoint(TASK_ID, body));
    }

    // -----------------------------------------------------------------------
    // file_rewind mode
    // -----------------------------------------------------------------------

    @Test
    void testRewind_fileRewind_success() {
        ClaudeTaskEntity task = createCompletedTask();
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);

        // Step 1: rewindFiles
        Map<String, Object> fileResult = Map.of("status", "rewound");
        when(client.rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects"))
                .thenReturn(reactor.core.publisher.Mono.just(fileResult));

        // Step 2: rewindConversation
        Map<String, Object> convResult = Map.of(
                "status", "rewound",
                "user_prompt", "Original prompt"
        );
        when(client.rewindConversation(CLAUDE_SESSION_ID, 2))
                .thenReturn(reactor.core.publisher.Mono.just(convResult));

        // Step 3: truncate
        when(taskService.truncateSessionMessages(SESSION_ID, 2)).thenReturn(8);

        Map<String, Object> body = Map.of(
                "mode", "file_rewind",
                "checkpointId", "cp-1",
                "turnIndex", 2
        );

        RX<Map<String, Object>> rx = controller.rewindToCheckpoint(TASK_ID, body);

        assertEquals(200, rx.getCode());
        Map<String, Object> data = rx.getData();
        assertEquals("rewound", data.get("status"));
        assertEquals("cp-1", data.get("checkpointId"));
        assertEquals("Original prompt", data.get("userPrompt"));

        verify(client).rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects");
        verify(client).rewindConversation(CLAUDE_SESSION_ID, 2);
        verify(taskService).truncateSessionMessages(SESSION_ID, 2);
    }

    @Test
    void testRewind_fileRewind_missingCheckpointId() {
        ClaudeTaskEntity task = createCompletedTask();
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        Map<String, Object> body = Map.of(
                "mode", "file_rewind",
                "turnIndex", 1
        );
        // checkpointId missing → should throw

        assertThrows(RuntimeException.class, () ->
                controller.rewindToCheckpoint(TASK_ID, body));
    }

    @Test
    void testRewind_fileRewind_conversationFails_nonFatal() {
        ClaudeTaskEntity task = createCompletedTask();
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);

        // Step 1: files succeed
        Map<String, Object> fileResult = Map.of("status", "rewound");
        when(client.rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects"))
                .thenReturn(reactor.core.publisher.Mono.just(fileResult));

        // Step 2: conversation rewind fails (non-fatal)
        when(client.rewindConversation(CLAUDE_SESSION_ID, 1))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("JSONL write failed")));

        // Step 3: truncate
        when(taskService.truncateSessionMessages(SESSION_ID, 1)).thenReturn(0);

        Map<String, Object> body = Map.of(
                "mode", "file_rewind",
                "checkpointId", "cp-1",
                "turnIndex", 1
        );

        RX<Map<String, Object>> rx = controller.rewindToCheckpoint(TASK_ID, body);

        // Should still succeed (conversation rewind failure is non-fatal for file_rewind)
        assertEquals(200, rx.getCode());
        assertEquals("rewound", rx.getData().get("status"));
    }

    // -----------------------------------------------------------------------
    // Common error cases
    // -----------------------------------------------------------------------

    @Test
    void testRewind_runningTask_rejected() {
        ClaudeTaskEntity task = createTaskEntity("RUNNING");
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 1);

        assertThrows(RuntimeException.class, () ->
                controller.rewindToCheckpoint(TASK_ID, body));
    }

    @Test
    void testRewind_taskNotFound() {
        when(taskService.getTaskEntity("nonexistent")).thenThrow(
                new IllegalArgumentException("Task not found: nonexistent"));

        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 1);

        assertThrows(IllegalArgumentException.class, () ->
                controller.rewindToCheckpoint("nonexistent", body));
    }

    @Test
    void testRewind_noClaudeSessionId() {
        ClaudeTaskEntity task = createCompletedTask();
        task.setClaudeSessionId(null);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 1);

        assertThrows(RuntimeException.class, () ->
                controller.rewindToCheckpoint(TASK_ID, body));
    }

    @Test
    void testRewind_wrongUser() {
        ClaudeTaskEntity task = createCompletedTask();
        task.setUserId("other-user");
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 1);

        assertThrows(RuntimeException.class, () ->
                controller.rewindToCheckpoint(TASK_ID, body));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeTaskEntity createTaskEntity(String status) {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setStatus(status);
        entity.setCwd("D:\\projects");
        return entity;
    }

    private ClaudeTaskEntity createCompletedTask() {
        return createTaskEntity("COMPLETED");
    }

    private ClaudeWorkerEntity createWorkerEntity() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setStatus("ONLINE");
        return worker;
    }
}

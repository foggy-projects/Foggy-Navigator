package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Tests for WorkerStreamRelay.autoScanCheckpoints — the async checkpoint
 * scanning triggered on task completion or abort.
 *
 * Since autoScanCheckpoints internally subscribes on boundedElastic,
 * we test the logic by invoking the method directly (synchronous for tests)
 * and verifying the mock interactions.
 */
class WorkerStreamRelayCheckpointTest {

    private WorkerStreamRelay relay;
    private ClaudeTaskRepository taskRepository;
    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient mockClient;

    private static final String TASK_ID = "task-relay-001";
    private static final String CLAUDE_SESSION_ID = "claude-relay-001";
    private static final String WORKER_ID = "worker-relay-001";
    private static final String USER_ID = "user-relay-001";

    @BeforeEach
    void setUp() {
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        taskRepository = mock(ClaudeTaskRepository.class);
        mockClient = mock(ClaudeWorkerClient.class);

        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        var conversationConfigService = mock(ConversationConfigService.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var objectMapper = new ObjectMapper();

        relay = new WorkerStreamRelay(
                workerService,
                taskService,
                taskRepository,
                workingDirectoryRepository,
                conversationConfigService,
                publisher,
                objectMapper
        );
    }

    @Test
    void testAutoScanCheckpoints_callsScanAndSave() throws Exception {
        // Given: task exists with a worker
        ClaudeTaskEntity task = createTaskEntity();
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(mockClient);

        List<Map<String, Object>> scanned = List.of(
                Map.of("id", "cp-1", "turnIndex", 1, "timestamp", "t1"),
                Map.of("id", "cp-2", "turnIndex", 2, "timestamp", "t2")
        );
        when(mockClient.scanSessionCheckpoints(CLAUDE_SESSION_ID))
                .thenReturn(Mono.just(scanned));

        // When: autoScanCheckpoints is invoked
        // Note: the actual method uses Mono.fromRunnable().subscribeOn(boundedElastic)
        // We call autoScanCheckpoints which triggers async subscription.
        // In unit tests, we need to wait a bit or test the runnable directly.
        // Since the internal Mono subscribes asynchronously, let's verify by
        // checking with a small delay.
        relay.autoScanCheckpoints(TASK_ID, CLAUDE_SESSION_ID);

        // Give the boundedElastic thread a moment to execute
        Thread.sleep(500);

        // Then: taskService.scanAndPopulateCheckpoints was called with the scanned data
        verify(taskService).scanAndPopulateCheckpoints(TASK_ID, scanned);
    }

    @Test
    void testAutoScanCheckpoints_nullClaudeSessionId_skips() {
        relay.autoScanCheckpoints(TASK_ID, null);

        // No interactions with task repository or worker service
        verifyNoInteractions(taskRepository);
        verifyNoInteractions(workerService);
    }

    @Test
    void testAutoScanCheckpoints_emptyClaudeSessionId_skips() {
        relay.autoScanCheckpoints(TASK_ID, "");

        verifyNoInteractions(taskRepository);
        verifyNoInteractions(workerService);
    }

    @Test
    void testAutoScanCheckpoints_taskNotFound_noException() throws Exception {
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());

        // Should not throw — failure is logged, not propagated
        relay.autoScanCheckpoints(TASK_ID, CLAUDE_SESSION_ID);

        Thread.sleep(500);

        // scanAndPopulateCheckpoints should never be called
        verify(taskService, never()).scanAndPopulateCheckpoints(anyString(), anyList());
    }

    @Test
    void testAutoScanCheckpoints_workerError_noException() throws Exception {
        ClaudeTaskEntity task = createTaskEntity();
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity(WORKER_ID)).thenThrow(
                new IllegalArgumentException("Worker not found"));

        // Should not throw
        relay.autoScanCheckpoints(TASK_ID, CLAUDE_SESSION_ID);

        Thread.sleep(500);

        verify(taskService, never()).scanAndPopulateCheckpoints(anyString(), anyList());
    }

    @Test
    void testAutoScanCheckpoints_emptyCheckpoints_noSave() throws Exception {
        ClaudeTaskEntity task = createTaskEntity();
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));

        ClaudeWorkerEntity worker = createWorkerEntity();
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(mockClient);

        // Scanner returns empty list
        when(mockClient.scanSessionCheckpoints(CLAUDE_SESSION_ID))
                .thenReturn(Mono.just(List.of()));

        relay.autoScanCheckpoints(TASK_ID, CLAUDE_SESSION_ID);

        Thread.sleep(500);

        // Should NOT call scanAndPopulateCheckpoints with empty list
        verify(taskService, never()).scanAndPopulateCheckpoints(anyString(), anyList());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeTaskEntity createTaskEntity() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);
        entity.setStatus("COMPLETED");
        return entity;
    }

    private ClaudeWorkerEntity createWorkerEntity() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setStatus("ONLINE");
        return worker;
    }
}

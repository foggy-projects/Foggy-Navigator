package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClaudeTaskService checkpoint/rewind-related methods:
 * - scanAndPopulateCheckpoints
 * - truncateSessionMessages
 * - abortTask (checkpoint scan trigger)
 */
class ClaudeTaskServiceCheckpointTest {

    private ClaudeTaskService service;
    private ClaudeTaskRepository taskRepository;
    private SessionManager sessionManager;
    private WorkerStreamRelay streamRelay;
    private ClaudeWorkerService workerService;
    private TransactionTemplate txTemplate;

    private static final String TASK_ID = "task-cp-001";
    private static final String SESSION_ID = "session-cp-001";
    private static final String CLAUDE_SESSION_ID = "claude-session-cp-001";
    private static final String WORKER_ID = "worker-cp-001";
    private static final String USER_ID = "user-cp-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        sessionManager = mock(SessionManager.class);
        streamRelay = mock(WorkerStreamRelay.class);
        workerService = mock(ClaudeWorkerService.class);
        txTemplate = mock(TransactionTemplate.class);

        var conversationConfigRepository = mock(com.foggy.navigator.claude.worker.repository.ConversationConfigRepository.class);
        var deletedSessionRepository = mock(com.foggy.navigator.claude.worker.repository.DeletedClaudeSessionRepository.class);
        var configService = mock(ConversationConfigService.class);
        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var directoryService = mock(WorkingDirectoryService.class);
        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var llmModelManager = mock(LlmModelManager.class);
        var userAuthService = mock(UserAuthService.class);

        service = new ClaudeTaskService(
                taskRepository,
                conversationConfigRepository,
                deletedSessionRepository,
                workerService,
                configService,
                agentTeamsConfigService,
                directoryService,
                workingDirectoryRepository,
                sessionManager,
                publisher,
                llmModelManager,
                userAuthService,
                txTemplate
        );

        // Inject the lazy streamRelay via reflection (it's @Autowired @Lazy)
        try {
            var field = ClaudeTaskService.class.getDeclaredField("streamRelay");
            field.setAccessible(true);
            field.set(service, streamRelay);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject streamRelay", e);
        }
    }

    // -----------------------------------------------------------------------
    // scanAndPopulateCheckpoints
    // -----------------------------------------------------------------------

    @Test
    void testScanAndPopulateCheckpoints_success() {
        ClaudeTaskEntity entity = createTaskEntity();
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> checkpoints = List.of(
                Map.of("id", "uuid-1", "turnIndex", 1, "timestamp", "2026-03-10T10:00:00Z"),
                Map.of("id", "uuid-2", "turnIndex", 2, "timestamp", "2026-03-10T10:01:00Z")
        );

        String json = service.scanAndPopulateCheckpoints(TASK_ID, checkpoints);

        assertNotNull(json);
        assertTrue(json.contains("uuid-1"));
        assertTrue(json.contains("uuid-2"));
        verify(taskRepository).save(entity);
        assertNotNull(entity.getCheckpoints());
    }

    @Test
    void testScanAndPopulateCheckpoints_taskNotFound() {
        when(taskRepository.findByTaskId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.scanAndPopulateCheckpoints("nonexistent", List.of()));
    }

    @Test
    void testScanAndPopulateCheckpoints_emptyList() {
        ClaudeTaskEntity entity = createTaskEntity();
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String json = service.scanAndPopulateCheckpoints(TASK_ID, List.of());

        assertNotNull(json);
        assertEquals("[]", json);
        verify(taskRepository).save(entity);
    }

    // -----------------------------------------------------------------------
    // truncateSessionMessages
    // -----------------------------------------------------------------------

    @Test
    void testTruncateSessionMessages_correctDeletion() {
        when(sessionManager.truncateMessagesFromTurn(SESSION_ID, 2)).thenReturn(10);

        int deleted = service.truncateSessionMessages(SESSION_ID, 2);

        assertEquals(10, deleted);
        verify(sessionManager).truncateMessagesFromTurn(SESSION_ID, 2);
    }

    @Test
    void testTruncateSessionMessages_noMessagesToDelete() {
        when(sessionManager.truncateMessagesFromTurn(SESSION_ID, 5)).thenReturn(0);

        int deleted = service.truncateSessionMessages(SESSION_ID, 5);

        assertEquals(0, deleted);
    }

    // -----------------------------------------------------------------------
    // abortTask — checkpoint scan trigger
    // -----------------------------------------------------------------------

    @Test
    void testAbortTask_triggersCheckpointScan() {
        ClaudeTaskEntity entity = createTaskEntity();
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);

        when(streamRelay.getWorkerTaskId(TASK_ID)).thenReturn("worker-task-1");
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

        ClaudeWorkerClient mockClient = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(any())).thenReturn(mockClient);
        when(mockClient.abortTask(anyString())).thenReturn(reactor.core.publisher.Mono.empty());

        // txTemplate should execute the consumer
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service.abortTask(TASK_ID);

        // Verify step 5: autoScanCheckpoints was called
        verify(streamRelay).autoScanCheckpoints(TASK_ID, CLAUDE_SESSION_ID);
    }

    @Test
    void testAbortTask_noClaudeSessionId_skipsCheckpointScan() {
        ClaudeTaskEntity entity = createTaskEntity();
        entity.setClaudeSessionId(null); // No Claude session

        when(streamRelay.getWorkerTaskId(TASK_ID)).thenReturn("worker-task-1");
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

        ClaudeWorkerClient mockClient = mock(ClaudeWorkerClient.class);
        when(workerService.createClient(any())).thenReturn(mockClient);
        when(mockClient.abortTask(anyString())).thenReturn(reactor.core.publisher.Mono.empty());

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service.abortTask(TASK_ID);

        // autoScanCheckpoints should NOT be called (no claudeSessionId)
        verify(streamRelay, never()).autoScanCheckpoints(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeTaskEntity createTaskEntity() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setStatus("RUNNING");
        entity.setCwd("D:\\projects");
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

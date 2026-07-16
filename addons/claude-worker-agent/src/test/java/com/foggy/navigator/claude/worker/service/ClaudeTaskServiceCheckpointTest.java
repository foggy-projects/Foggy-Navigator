package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
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
 * - checkpoint scans only after a terminal Worker observation
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
    private static final String TENANT_ID = "tenant-cp-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        sessionManager = mock(SessionManager.class);
        streamRelay = mock(WorkerStreamRelay.class);
        workerService = mock(ClaudeWorkerService.class);
        txTemplate = mock(TransactionTemplate.class);

        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var directoryService = mock(WorkingDirectoryService.class);
        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var llmModelManager = mock(LlmModelManager.class);
        var userAuthService = mock(UserAuthService.class);
        var credentialEncryptor = mock(com.foggy.navigator.common.security.CredentialEncryptor.class);

        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);
        service = new ClaudeTaskService(
                taskRepository,
                workerService,
                agentTeamsConfigService,
                codingAgentRepository,
                directoryService,
                workingDirectoryRepository,
                sessionManager,
                publisher,
                llmModelManager,
                userAuthService,
                credentialEncryptor,
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
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
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
        when(taskRepository.findByTaskIdForUpdate("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.scanAndPopulateCheckpoints("nonexistent", List.of()));
    }

    @Test
    void testScanAndPopulateCheckpoints_emptyList() {
        ClaudeTaskEntity entity = createTaskEntity();
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
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
    // cancellation does not fabricate a terminal checkpoint scan
    // -----------------------------------------------------------------------

    @Test
    void testAbortTaskWithoutTerminalEvidenceDoesNotScanCheckpoints() {
        ClaudeTaskEntity entity = createTaskEntity();
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);

        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.abortTask(TASK_ID);

        // A cancel request is not completion.  Checkpoints are scanned by the
        // relay only once it receives a verified terminal Worker event.
        verify(streamRelay, never()).autoScanCheckpoints(anyString(), anyString());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", entity.getErrorMessage());
    }

    @Test
    void testAbortTaskWithoutClaudeSessionIdStillDoesNotScanCheckpoints() {
        ClaudeTaskEntity entity = createTaskEntity();
        entity.setClaudeSessionId(null); // No Claude session

        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.abortTask(TASK_ID);

        // A cancellation acknowledgement alone never prompts a scan.
        verify(streamRelay, never()).autoScanCheckpoints(anyString(), anyString());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
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
        entity.setTenantId(TENANT_ID);
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

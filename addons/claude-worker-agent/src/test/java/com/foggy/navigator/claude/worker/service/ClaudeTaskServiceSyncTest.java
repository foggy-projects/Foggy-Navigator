package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClaudeTaskService.syncLocalSessions — cross-platform path matching.
 *
 * Worker sessions may report Windows, WSL, or slash-normalized paths for the
 * same directory. Exact path candidates are preferred; a unique leaf-name
 * match is used only as a final fallback.
 */
class ClaudeTaskServiceSyncTest {

    private ClaudeTaskRepository taskRepository;
    private WorkingDirectoryRepository directoryRepository;
    private SessionEntityRepository sessionEntityRepository;
    private SessionManager sessionManager;
    private ClaudeTaskService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        directoryRepository = mock(WorkingDirectoryRepository.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);
        sessionManager = mock(SessionManager.class);

        ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
        WorkingDirectoryService dirService = mock(WorkingDirectoryService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        LlmModelManager llmModelManager = mock(LlmModelManager.class);
        UserAuthService userAuthService = mock(UserAuthService.class);
        var credentialEncryptor = mock(com.foggy.navigator.common.security.CredentialEncryptor.class);
        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);
        service = new ClaudeTaskService(
                taskRepository, workerService,
                agentTeamsConfigService, codingAgentRepository, dirService, directoryRepository, sessionManager, publisher, llmModelManager,
                userAuthService, credentialEncryptor,
                mock(org.springframework.transaction.support.TransactionTemplate.class));
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);

        // Session creation returns a predictable ID
        when(sessionManager.createSession(any(SessionCreateRequest.class)))
                .thenReturn("session-001");

        // No existing tasks
        when(taskRepository.existsByClaudeSessionIdAndWorkerId(anyString(), anyString()))
                .thenReturn(false);

        // No orphan tasks to backfill by default
        when(taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(anyString(), anyString()))
                .thenReturn(List.of());
        when(directoryRepository.findByWorkerIdAndUserIdOrderByProjectNameAsc(anyString(), anyString()))
                .thenReturn(List.of());

        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionEntityRepository.findById(anyString())).thenReturn(Optional.empty());
        when(sessionEntityRepository.findDeletedByWorkerIdAndUserId(anyString(), anyString()))
                .thenReturn(List.of());
    }

    @Test
    void syncLocalSessions_exactPathMatch_setsDirectoryId() {
        // Directory stored with forward slashes
        WorkingDirectoryEntity dir = createDirectory("dir-1", "D:/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        // Worker session returns the same forward-slash path
        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-aaa", "cwd", "D:/foggy-projects/student-analytics", "slug", "test task")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-1", captor.getValue().getDirectoryId());
    }

    @Test
    void syncLocalSessions_skipsDeletedSessionFromSchemaVersionedProviderState() {
        SessionEntity deletedSession = new SessionEntity();
        deletedSession.setId("deleted-session");
        deletedSession.setProviderStateJson(ProviderStateCodec.mergeSessionValue(
                null,
                "claude-worker",
                ProviderStateCodec.FIELD_CLAUDE_SESSION_ID,
                "sess-deleted"));
        when(sessionEntityRepository.findDeletedByWorkerIdAndUserId(WORKER_ID, USER_ID))
                .thenReturn(List.of(deletedSession));
        when(directoryRepository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-deleted", "cwd", "D:/deleted", "slug", "deleted"),
                Map.of("session_id", "sess-new", "cwd", "D:/active", "slug", "active")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("sess-new", captor.getValue().getClaudeSessionId());
    }

    @Test
    void syncLocalSessions_wslCwd_matchesWindowsDirectory() {
        WorkingDirectoryEntity dir = createDirectory("dir-1", "D:/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-bbb", "cwd", "/mnt/d/foggy-projects/student-analytics", "slug", "fix bug")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-1", captor.getValue().getDirectoryId(),
                "WSL cwd should match the equivalent Windows working directory");
    }

    @Test
    void syncLocalSessions_windowsCwd_matchesWslDirectory() {
        WorkingDirectoryEntity dir = createDirectory("dir-2", "/mnt/d/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "/mnt/d/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-ccc", "cwd", "D:\\foggy-projects\\student-analytics", "slug", "add feature")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-2", captor.getValue().getDirectoryId(),
                "Windows cwd should match the equivalent WSL working directory");
    }

    @Test
    void syncLocalSessions_uniqueLeafFallback_setsDirectoryId() {
        WorkingDirectoryEntity dir = createDirectory("dir-leaf", "D:/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndUserIdOrderByProjectNameAsc(WORKER_ID, USER_ID))
                .thenReturn(List.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-leaf", "cwd", "/home/sa/workspace/student-analytics", "slug", "fallback")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-leaf", captor.getValue().getDirectoryId());
    }

    @Test
    void syncLocalSessions_ambiguousLeafFallback_leavesDirectoryIdNull() {
        WorkingDirectoryEntity first = createDirectory("dir-first", "D:/projects/student-analytics");
        WorkingDirectoryEntity second = createDirectory("dir-second", "E:/archive/student-analytics");
        when(directoryRepository.findByWorkerIdAndUserIdOrderByProjectNameAsc(WORKER_ID, USER_ID))
                .thenReturn(List.of(first, second));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-ambiguous", "cwd", "/home/sa/workspace/student-analytics", "slug", "ambiguous")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertNull(captor.getValue().getDirectoryId());
    }

    @Test
    void syncLocalSessions_noMatchEitherWay_directoryIdNull() {
        // No directory matches any variant
        when(directoryRepository.findByWorkerIdAndPathAndUserId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-ddd", "cwd", "C:\\other\\path", "slug", "unrelated")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertNull(captor.getValue().getDirectoryId(),
                "directoryId should be null when no directory matches");
    }

    @Test
    void syncLocalSessions_nullCwd_directoryIdNull() {
        // Backfill returns empty for this worker
        when(taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(WORKER_ID, USER_ID))
                .thenReturn(List.of());

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-eee", "slug", "no cwd")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        // save called twice: once for new task, once checked by backfill (but no orphans)
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository, atLeastOnce()).save(captor.capture());
        // The first save is the new task
        assertNull(captor.getAllValues().get(0).getDirectoryId());
    }

    @Test
    void syncLocalSessions_backfillsExistingOrphanTasks() {
        // All sessions already synced (dedup)
        when(taskRepository.existsByClaudeSessionIdAndWorkerId(anyString(), anyString()))
                .thenReturn(true);

        // But there are existing orphan tasks with null directoryId
        ClaudeTaskEntity orphan = new ClaudeTaskEntity();
        orphan.setTaskId("old-task");
        orphan.setWorkerId(WORKER_ID);
        orphan.setUserId(USER_ID);
        orphan.setCwd("/mnt/d/foggy-projects/student-analytics");
        orphan.setDirectoryId(null);
        when(taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(WORKER_ID, USER_ID))
                .thenReturn(List.of(orphan));

        WorkingDirectoryEntity dir = createDirectory("dir-sa", "D:/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-existing", "cwd", "/mnt/d/foggy-projects/student-analytics")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(0, created, "No new tasks created (dedup)");
        // But the orphan should have been backfilled
        assertEquals("dir-sa", orphan.getDirectoryId(),
                "Orphan task should have its directoryId backfilled");
        verify(taskRepository).save(orphan);
    }

    private WorkingDirectoryEntity createDirectory(String directoryId, String path) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setProjectName("test-" + directoryId);
        entity.setPath(path);
        entity.setDirectoryType("STANDARD");
        return entity;
    }
}

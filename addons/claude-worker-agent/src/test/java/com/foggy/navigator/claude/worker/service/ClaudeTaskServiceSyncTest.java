package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClaudeTaskService.syncLocalSessions — path normalization.
 *
 * Root cause: Worker returns cwd with backslashes (D:\foo\bar) on Windows,
 * but WorkingDirectory may store the path with forward slashes (D:/foo/bar).
 * The exact DB match fails, so synced tasks get directoryId=null and don't
 * appear in any directory's task list.
 */
class ClaudeTaskServiceSyncTest {

    private ClaudeTaskRepository taskRepository;
    private WorkingDirectoryRepository directoryRepository;
    private SessionManager sessionManager;
    private ClaudeTaskService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        directoryRepository = mock(WorkingDirectoryRepository.class);
        sessionManager = mock(SessionManager.class);

        ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
        ConversationConfigService configService = mock(ConversationConfigService.class);
        WorkingDirectoryService dirService = mock(WorkingDirectoryService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        LlmModelManager llmModelManager = mock(LlmModelManager.class);
        UserAuthService userAuthService = mock(UserAuthService.class);
        service = new ClaudeTaskService(
                taskRepository, workerService, configService,
                dirService, directoryRepository, sessionManager, publisher, llmModelManager,
                userAuthService);

        // Session creation returns a predictable ID
        when(sessionManager.createSession(any(SessionCreateRequest.class)))
                .thenReturn("session-001");

        // No existing tasks
        when(taskRepository.existsByClaudeSessionIdAndWorkerId(anyString(), anyString()))
                .thenReturn(false);

        // No orphan tasks to backfill by default
        when(taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(anyString(), anyString()))
                .thenReturn(List.of());

        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void syncLocalSessions_backslashCwd_matchesForwardSlashDirectory() {
        // Directory stored with FORWARD slashes (as entered via frontend)
        WorkingDirectoryEntity dir = createDirectory("dir-1", "D:/foggy-projects/student-analytics");

        // First lookup with original backslash cwd → miss
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:\\foggy-projects\\student-analytics", USER_ID))
                .thenReturn(Optional.empty());
        // Second lookup with normalized forward-slash cwd → hit
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        // Worker returns cwd with BACKSLASHES (Windows native)
        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-bbb", "cwd", "D:\\foggy-projects\\student-analytics", "slug", "fix bug")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-1", captor.getValue().getDirectoryId(),
                "directoryId should be set even when cwd uses backslashes but directory uses forward slashes");
    }

    @Test
    void syncLocalSessions_forwardSlashCwd_matchesBackslashDirectory() {
        // Directory stored with BACKSLASHES
        WorkingDirectoryEntity dir = createDirectory("dir-2", "D:\\foggy-projects\\student-analytics");

        // First lookup with original forward-slash cwd → miss
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.empty());
        // Second lookup with normalized backslash cwd → hit
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:\\foggy-projects\\student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-ccc", "cwd", "D:/foggy-projects/student-analytics", "slug", "add feature")
        );

        int created = service.syncLocalSessions(USER_ID, TENANT_ID, WORKER_ID, sessions);

        assertEquals(1, created);
        ArgumentCaptor<ClaudeTaskEntity> captor = ArgumentCaptor.forClass(ClaudeTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("dir-2", captor.getValue().getDirectoryId(),
                "directoryId should be set even when cwd uses forward slashes but directory uses backslashes");
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
        orphan.setCwd("D:\\foggy-projects\\student-analytics");
        orphan.setDirectoryId(null);
        when(taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(WORKER_ID, USER_ID))
                .thenReturn(List.of(orphan));

        // Directory stored with forward slashes
        WorkingDirectoryEntity dir = createDirectory("dir-sa", "D:/foggy-projects/student-analytics");
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:\\foggy-projects\\student-analytics", USER_ID))
                .thenReturn(Optional.empty());
        when(directoryRepository.findByWorkerIdAndPathAndUserId(
                WORKER_ID, "D:/foggy-projects/student-analytics", USER_ID))
                .thenReturn(Optional.of(dir));

        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "sess-existing", "cwd", "D:\\foggy-projects\\student-analytics")
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

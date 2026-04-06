package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Tests for abort/cancel guard logic in ClaudeTaskService:
 * <ul>
 *   <li>failTask() skips when abortRequested=true</li>
 *   <li>failTask() skips when task already in terminal state</li>
 *   <li>doAbortWorkerTask() sets and clears abortRequested flag</li>
 *   <li>doAbortWorkerTask() skips ABORTED update when already terminal</li>
 * </ul>
 */
class ClaudeTaskServiceAbortGuardTest {

    private ClaudeTaskService service;
    private ClaudeTaskRepository taskRepository;
    private WorkerStreamRelay streamRelay;
    private ClaudeWorkerService workerService;
    private TransactionTemplate txTemplate;
    private ApplicationEventPublisher publisher;

    private static final String TASK_ID = "task-abort-001";
    private static final String SESSION_ID = "session-abort-001";
    private static final String WORKER_ID = "worker-abort-001";
    private static final String USER_ID = "user-abort-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        streamRelay = mock(WorkerStreamRelay.class);
        workerService = mock(ClaudeWorkerService.class);
        txTemplate = mock(TransactionTemplate.class);
        publisher = mock(ApplicationEventPublisher.class);

        var sessionManager = mock(SessionManager.class);
        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var directoryService = mock(WorkingDirectoryService.class);
        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
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

        // Inject the lazy streamRelay via reflection
        try {
            var field = ClaudeTaskService.class.getDeclaredField("streamRelay");
            field.setAccessible(true);
            field.set(service, streamRelay);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject streamRelay", e);
        }
    }

    // -----------------------------------------------------------------------
    // failTask — abortRequested guard
    // -----------------------------------------------------------------------

    @Nested
    class FailTaskGuardTests {

        @Test
        void failTask_skipsWhenAbortRequested() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(true);
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "Task was cancelled");

            // Should NOT update status — cancel thread will handle
            verify(taskRepository, never()).save(any());
            // Status should remain RUNNING (not changed to FAILED)
            org.junit.jupiter.api.Assertions.assertEquals("RUNNING", entity.getStatus());
        }

        @Test
        void failTask_skipsWhenAlreadyAborted() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("ABORTED");
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "Task was cancelled");

            verify(taskRepository, never()).save(any());
            org.junit.jupiter.api.Assertions.assertEquals("ABORTED", entity.getStatus());
        }

        @Test
        void failTask_skipsWhenAlreadyCompleted() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("COMPLETED");
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "some error");

            verify(taskRepository, never()).save(any());
            org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", entity.getStatus());
        }

        @Test
        void failTask_proceedsNormally_whenNoGuardTriggered() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(false);
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "real error");

            org.junit.jupiter.api.Assertions.assertEquals("FAILED", entity.getStatus());
            org.junit.jupiter.api.Assertions.assertEquals("real error", entity.getErrorMessage());
            verify(taskRepository).save(entity);
        }

        @Test
        void failTask_proceedsNormally_whenAbortRequestedIsNull() {
            // null = field not set (e.g., legacy task) → treat as false
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(null);
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "real error");

            org.junit.jupiter.api.Assertions.assertEquals("FAILED", entity.getStatus());
            verify(taskRepository).save(entity);
        }
    }

    // -----------------------------------------------------------------------
    // doAbortWorkerTask — abortRequested lifecycle
    // -----------------------------------------------------------------------

    @Nested
    class DoAbortWorkerTaskTests {

        @BeforeEach
        void setUpTxTemplate() {
            // txTemplate should execute the consumer synchronously
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var consumer = (java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0);
                consumer.accept(null);
                return null;
            }).when(txTemplate).executeWithoutResult(any());
        }

        @Test
        void doAbortWorkerTask_setsAbortRequestedBeforeWorkerNotification() {
            ClaudeTaskEntity entity = createRunningTask();
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

            var mockClient = mock(com.foggy.navigator.claude.worker.client.ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(mockClient);
            when(mockClient.abortTask("remote-task-1")).thenReturn(reactor.core.publisher.Mono.empty());

            service.doAbortWorkerTask(TASK_ID, "remote-task-1");

            // Verify: abortRequested was set via repository UPDATE
            verify(taskRepository).updateAbortRequestedByTaskId(TASK_ID, true);
            // Verify: worker was notified
            verify(mockClient).abortTask("remote-task-1");
            // Verify: stream was cleaned
            verify(streamRelay).abortStream(TASK_ID);
            // Verify: final status is ABORTED
            org.junit.jupiter.api.Assertions.assertEquals("ABORTED", entity.getStatus());
            // Verify: abortRequested is cleared
            org.junit.jupiter.api.Assertions.assertEquals(false, entity.getAbortRequested());
        }

        @Test
        void doAbortWorkerTask_skipsStatusUpdate_whenAlreadyTerminal() {
            // Simulate: reactor thread already set FAILED before our txTemplate runs
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("FAILED");
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.doAbortWorkerTask(TASK_ID, null);

            // abortRequested flag was still set (step 1 always runs)
            verify(taskRepository).updateAbortRequestedByTaskId(TASK_ID, true);
            // Status should remain FAILED — not overwritten to ABORTED
            org.junit.jupiter.api.Assertions.assertEquals("FAILED", entity.getStatus());
            // abortRequested should be cleared
            org.junit.jupiter.api.Assertions.assertEquals(false, entity.getAbortRequested());
        }

        @Test
        void doAbortWorkerTask_handlesWorkerOffline() {
            ClaudeTaskEntity entity = createRunningTask();
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID)).thenThrow(new RuntimeException("Worker offline"));

            // Should NOT throw — worker notification failure is caught
            service.doAbortWorkerTask(TASK_ID, "remote-task-1");

            // abortRequested was still set
            verify(taskRepository).updateAbortRequestedByTaskId(TASK_ID, true);
            // Stream still cleaned
            verify(streamRelay).abortStream(TASK_ID);
            // Status still updated to ABORTED (worker offline doesn't block local state update)
            org.junit.jupiter.api.Assertions.assertEquals("ABORTED", entity.getStatus());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeTaskEntity createRunningTask() {
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

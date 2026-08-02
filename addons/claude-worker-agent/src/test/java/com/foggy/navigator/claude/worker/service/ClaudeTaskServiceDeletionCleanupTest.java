package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeTaskServiceDeletionCleanupTest {

    private ClaudeTaskRepository taskRepository;
    private WorkerStreamRelay streamRelay;
    private ClaudeTaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        streamRelay = mock(WorkerStreamRelay.class);
        service = new ClaudeTaskService(
                taskRepository,
                mock(ClaudeWorkerService.class),
                mock(AgentTeamsConfigService.class),
                mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class),
                mock(WorkingDirectoryService.class),
                mock(WorkingDirectoryRepository.class),
                mock(SessionManager.class),
                mock(ApplicationEventPublisher.class),
                mock(LlmModelManager.class),
                mock(UserAuthService.class),
                mock(CredentialEncryptor.class),
                mock(TransactionTemplate.class));
        ReflectionTestUtils.setField(service, "streamRelay", streamRelay);

        ClaudeTaskEntity task = new ClaudeTaskEntity();
        task.setTaskId("task-1");
        task.setUserId("user-1");
        task.setStatus("COMPLETED");
        when(taskRepository.findByTaskIdForUpdate("task-1")).thenReturn(Optional.of(task));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void committedDeleteClearsRelayOnlyAfterCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.deleteTask("user-1", "task-1");
        verify(streamRelay, never()).clearDeletedTask("task-1");

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(streamRelay).clearDeletedTask("task-1");
    }

    @Test
    void rolledBackDeleteDoesNotClearRelayState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.deleteTask("user-1", "task-1");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(streamRelay, never()).clearDeletedTask("task-1");
    }
}

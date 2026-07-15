package com.foggy.navigator.business.agent.event;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BusinessTaskScopedTokenTerminalListenerTest {

    @Mock
    private BusinessTaskScopedTokenLifecycleService tokenLifecycleService;

    @ParameterizedTest
    @ValueSource(strings = {
            "COMPLETED", "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT",
            "ABORTED", "CANCELLED", "CANCELED"
    })
    void definitiveTerminalStatus_recordsThenMaterializesTenantScopedTerminalState(String status) {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId(" worker_task_01 ")
                .tenantId(" tenant_01 ")
                .userId(" actor_01 ")
                .agentId(" langgraph-worker ")
                .status(status.toLowerCase())
                .recoverable(false)
                .build();
        BusinessTaskScopedTokenTerminalListener listener = listener();

        listener.recordTerminalStateBeforeCommit(event);
        listener.materializeRevocationAfterCommit(event);

        verify(tokenLifecycleService).recordTerminalState(
                "tenant_01",
                "worker_task_01",
                "actor_01",
                "langgraph-worker",
                status);
        verify(tokenLifecycleService).materializeTerminalRevocation(
                "tenant_01",
                "worker_task_01",
                BusinessTaskScopedTokenTerminalListener.REVOKED_BY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "PENDING", "AWAITING_PERMISSION", "AWAITING_INPUT"})
    void recoverableOrNonTerminalStatus_doesNotRevoke(String status) {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .status(status)
                .build();
        listener().recordTerminalStateBeforeCommit(event);
        listener().materializeRevocationAfterCommit(event);

        verifyNoInteractions(tokenLifecycleService);
    }

    @Test
    void explicitlyRecoverableTerminalStatus_doesNotCloseCapability() {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .status("FAILED")
                .recoverable(true)
                .build();

        listener().recordTerminalStateBeforeCommit(event);
        listener().materializeRevocationAfterCommit(event);

        verifyNoInteractions(tokenLifecycleService);
    }

    @Test
    void recoverableFailureThenRunningRecoveryNeverCreatesOrMaterializesTombstone() {
        BusinessTaskScopedTokenTerminalListener listener = listener();
        listener.recordTerminalStateBeforeCommit(TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .agentId("claude-worker")
                .status("FAILED")
                .recoverable(true)
                .build());
        listener.materializeRevocationAfterCommit(TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .agentId("claude-worker")
                .status("FAILED")
                .recoverable(true)
                .build());
        listener.recordTerminalStateBeforeCommit(TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .agentId("claude-worker")
                .status("RUNNING")
                .previousStatus("FAILED")
                .build());

        verifyNoInteractions(tokenLifecycleService);
    }

    @Test
    void terminalStatusWithoutExplicitDefinitiveFlagDoesNotCloseCapability() {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .status("FAILED")
                .recoverable(null)
                .build();

        listener().recordTerminalStateBeforeCommit(event);
        listener().materializeRevocationAfterCommit(event);

        verifyNoInteractions(tokenLifecycleService);
    }

    @Test
    void incompleteEvent_doesNotRevoke() {
        listener().recordTerminalStateBeforeCommit(null);
        listener().recordTerminalStateBeforeCommit(TaskStatusChangeEvent.builder()
                .taskId("worker_task_01").userId("actor_01").status("COMPLETED").build());
        listener().recordTerminalStateBeforeCommit(TaskStatusChangeEvent.builder()
                .tenantId("tenant_01").status("COMPLETED").recoverable(false).build());

        verifyNoInteractions(tokenLifecycleService);
    }

    @Test
    void materializationFailure_doesNotRemoveAuthoritativeTombstoneAndReplayRetries() {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId("worker_task_01")
                .tenantId("tenant_01")
                .userId("actor_01")
                .agentId("codex-biz")
                .status("COMPLETED")
                .recoverable(false)
                .build();
        doThrow(new IllegalStateException("database unavailable"))
                .doReturn(1)
                .when(tokenLifecycleService)
                .materializeTerminalRevocation(
                        "tenant_01", "worker_task_01",
                        BusinessTaskScopedTokenTerminalListener.REVOKED_BY);

        BusinessTaskScopedTokenTerminalListener listener = listener();
        listener.recordTerminalStateBeforeCommit(event);
        listener.materializeRevocationAfterCommit(event);
        listener.materializeRevocationAfterCommit(event);

        verify(tokenLifecycleService).recordTerminalState(
                "tenant_01", "worker_task_01", "actor_01", "codex-biz", "COMPLETED");
        verify(tokenLifecycleService, org.mockito.Mockito.times(2))
                .materializeTerminalRevocation(
                        "tenant_01", "worker_task_01",
                        BusinessTaskScopedTokenTerminalListener.REVOKED_BY);
    }

    @Test
    void listenerRunsAfterCommitAndFallsBackWithoutTransaction() throws Exception {
        TransactionalEventListener annotation = BusinessTaskScopedTokenTerminalListener.class
                .getMethod("recordTerminalStateBeforeCommit", TaskStatusChangeEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.BEFORE_COMMIT, annotation.phase());
        assertTrue(annotation.fallbackExecution());

        TransactionalEventListener materializeAnnotation = BusinessTaskScopedTokenTerminalListener.class
                .getMethod("materializeRevocationAfterCommit", TaskStatusChangeEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertNotNull(materializeAnnotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, materializeAnnotation.phase());
        assertTrue(materializeAnnotation.fallbackExecution());
    }

    private BusinessTaskScopedTokenTerminalListener listener() {
        return new BusinessTaskScopedTokenTerminalListener(tokenLifecycleService);
    }
}

package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTerminationAcceptanceCoordinatorTest {

    @Test
    void receiptAndExactDeliveryCommitInOneTransaction() {
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents =
                mock(RuntimeTerminationIntentPort.class);
        PlatformTransactionManager transactions = transactionManager();
        var registration =
                new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle("request"), false);
        when(audits.beginTaskOperationIdempotentAtomic(
                "request",
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user", "task"))
                .thenReturn(registration);

        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions);
        coordinator.accept(
                "request", "key", "secret", "user", "task",
                "session", "codex-biz-worker", "worker", "provider-task");

        var order = inOrder(audits, intents, transactions);
        order.verify(audits).beginTaskOperationIdempotentAtomic(
                "request",
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user", "task");
        order.verify(intents).recordIntent(any());
        order.verify(transactions).commit(any());
    }

    @Test
    void deliveryPersistenceFailureRollsBackAcceptanceTransaction() {
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents =
                mock(RuntimeTerminationIntentPort.class);
        PlatformTransactionManager transactions = transactionManager();
        when(audits.beginTaskOperationIdempotentAtomic(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeRequestAuditService
                        .TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle("request"),
                        false));
        when(intents.recordIntent(any()))
                .thenThrow(new IllegalStateException("FIXTURE_OUTBOX_FAILED"));

        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions);

        assertThatThrownBy(() -> coordinator.accept(
                "request", "key", "secret", "user", "task",
                "session", "codex-biz-worker", "worker", "provider-task"))
                .hasMessage("FIXTURE_OUTBOX_FAILED");
        verify(transactions).rollback(any());
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager =
                mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}

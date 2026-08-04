package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeTerminationAcceptanceCoordinatorTest {

    private final VerifiedCommandAuthorizationDecision.ServerAuthority canonicalAuthority =
            new VerifiedCommandAuthorizationDecision.ServerAuthority(
                    "runtime-termination-test-v1",
                    Clock.systemUTC(),
                    Duration.ofMinutes(5));

    @Test
    void preRegisteredReceiptIsVerifiedBeforeExactDeliveryCommits() {
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents =
                mock(RuntimeTerminationIntentPort.class);
        RuntimeTaskClosureProvider provider =
                mock(RuntimeTaskClosureProvider.class);
        PlatformTransactionManager transactions = transactionManager();
        var registration =
                new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle("request"), false);
        when(audits.beginTaskOperationIdempotentAtomic(
                "request",
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user", "task"))
                .thenReturn(new RuntimeRequestAuditService.TaskOperationRegistration(
                        registration.handle(), true));
        when(provider.prepareTerminationAdmission(
                "task", "owner", "tenant", "worker",
                "reason", "request"))
                .thenReturn(admission());

        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions,
                        canonicalAuthority);
        coordinator.accept(
                "request", "key", "secret", "user", "reason",
                provider, owned("worker"), authorization());

        var order = inOrder(provider, audits, intents, transactions);
        order.verify(audits).beginTaskOperationIdempotentAtomic(
                "request",
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user", "task");
        order.verify(provider).prepareTerminationAdmission(
                "task", "owner", "tenant", "worker",
                "reason", "request");
        order.verify(intents).recordIntent(any());
        order.verify(transactions).commit(any());
    }

    @Test
    void deliveryPersistenceFailureRollsBackAcceptanceTransaction() {
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents =
                mock(RuntimeTerminationIntentPort.class);
        RuntimeTaskClosureProvider provider =
                mock(RuntimeTaskClosureProvider.class);
        PlatformTransactionManager transactions = transactionManager();
        when(audits.beginTaskOperationIdempotentAtomic(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeRequestAuditService
                        .TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle("request"), true));
        when(intents.recordIntent(any()))
                .thenThrow(new IllegalStateException("FIXTURE_OUTBOX_FAILED"));
        when(provider.prepareTerminationAdmission(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(admission());

        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions,
                        canonicalAuthority);

        assertThatThrownBy(() -> coordinator.accept(
                "request", "key", "secret", "user", "reason",
                provider, owned("worker"), authorization()))
                .hasMessage("FIXTURE_OUTBOX_FAILED");
        verify(transactions).rollback(any());
    }

    @Test
    void missingPreRegisteredReceiptRejectsBeforeExactAdmissionOrIntent() {
        RuntimeRequestAuditService audits =
                mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents =
                mock(RuntimeTerminationIntentPort.class);
        RuntimeTaskClosureProvider provider =
                mock(RuntimeTaskClosureProvider.class);
        PlatformTransactionManager transactions = transactionManager();
        when(audits.beginTaskOperationIdempotentAtomic(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle("request"), false));
        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions,
                        canonicalAuthority);

        assertThatThrownBy(() -> coordinator.accept(
                "request", "key", "secret", "user", "reason",
                provider, owned("worker"), authorization()))
                .hasMessage("TERMINATION_REQUEST_RECEIPT_REQUIRED");

        verifyNoInteractions(provider, intents);
        verify(transactions).rollback(any());
    }

    @Test
    void authorizationBindingDriftRejectsBeforeReceiptAdmissionOrProvider() {
        RuntimeRequestAuditService audits = mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents = mock(RuntimeTerminationIntentPort.class);
        RuntimeTaskClosureProvider provider = mock(RuntimeTaskClosureProvider.class);
        PlatformTransactionManager transactions = transactionManager();
        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions,
                        canonicalAuthority);

        assertThatThrownBy(() -> coordinator.accept(
                "request", "key", "secret", "user", "reason",
                provider, owned("worker-other"), authorization()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("TERMINATION_AUTHORIZATION_BINDING_CONFLICT");

        verifyNoInteractions(audits, provider, intents);
        verify(transactions).rollback(any());
    }

    @Test
    void foreignAuthoritySealRejectsBeforeReceiptAdmissionOrProvider() {
        RuntimeRequestAuditService audits = mock(RuntimeRequestAuditService.class);
        RuntimeTerminationIntentPort intents = mock(RuntimeTerminationIntentPort.class);
        RuntimeTaskClosureProvider provider = mock(RuntimeTaskClosureProvider.class);
        PlatformTransactionManager transactions = transactionManager();
        RuntimeTerminationAcceptanceCoordinator coordinator =
                new RuntimeTerminationAcceptanceCoordinator(
                        audits, List.of(intents), transactions,
                        canonicalAuthority);
        var foreignAuthority =
                new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "foreign-runtime-termination-test-v1",
                        Clock.systemUTC(), Duration.ofMinutes(5));
        RuntimeTerminationCommandAuthorization foreignAuthorization =
                RuntimeTerminationCommandAuthorization.issue(
                        foreignAuthority, owned("worker"), "user", "request");

        assertThatThrownBy(() -> coordinator.accept(
                "request", "key", "secret", "user", "reason",
                provider, owned("worker"), foreignAuthorization))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(
                        "decision was not issued by this server authority");

        verifyNoInteractions(audits, provider, intents);
        verify(transactions).rollback(any());
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager =
                mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    private RuntimeTaskClosureProvider.TerminationAdmission admission() {
        return new RuntimeTaskClosureProvider.TerminationAdmission(
                "operation", "dispatch", "ENFORCED",
                "generation", "epoch", "JCS_SHA256_V1", "digest");
    }

    private RuntimeTerminationCommandAuthorization authorization() {
        return RuntimeTerminationCommandAuthorization.issue(
                canonicalAuthority, owned("worker"), "user", "request");
    }

    private RuntimeStateAuditService.OwnedRuntimeTask owned(String workerId) {
        return new RuntimeStateAuditService.OwnedRuntimeTask(
                "task", "session", "provider-task",
                "owner", "tenant", "codex-biz-worker", workerId,
                "RUNNING", false, 1,
                "agent", "model", "app", "credential", "user");
    }
}

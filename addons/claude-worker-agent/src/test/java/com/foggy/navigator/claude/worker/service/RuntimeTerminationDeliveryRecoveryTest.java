package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskClosureDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminationOutcome;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTerminationDeliveryRecoveryTest {
    private static final String REQUEST =
            "bb215535-4a33-413c-b9ef-ae8f487ac768";

    @Mock RuntimeStateAuditService stateAudit;
    @Mock RuntimeTaskClosureProvider provider;
    @Mock RuntimeRequestAuditService audits;
    @Mock RuntimeTerminationAcceptanceCoordinator coordinator;

    private RuntimeTaskClosureService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeTaskClosureService(
                stateAudit, List.of(provider), audits, coordinator);
        when(audits.terminationRequestReceiptEnabled()).thenReturn(true);
        when(stateAudit.requireOwnedTask(
                "key", "secret", "user", "task-delivery"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-delivery", "session-delivery",
                        "provider-task-delivery", "owner", "tenant",
                        "codex-biz-worker", "worker-delivery",
                        "RUNNING", false, 1));
        when(provider.supports("codex-biz-worker")).thenReturn(true);
        when(provider.inspect("task-delivery", "worker-delivery")).thenReturn(
                new RuntimeTaskClosureProvider.TerminationReadiness(
                        true, true, true, true, true, true, null));
        when(stateAudit.auditTask(
                "key", "secret", "user", "task-delivery"))
                .thenReturn(audit("RUNNING"), audit("CANCEL_REQUESTED"));
    }

    @Test
    void sameRequestRecoversCommittedPreparedDeliveryAfterCrashBeforeDispatch() {
        when(coordinator.accept(
                eq(REQUEST), eq("key"), eq("secret"), eq("user"),
                eq("task-delivery"), eq("session-delivery"),
                eq("codex-biz-worker"), eq("worker-delivery"),
                eq("provider-task-delivery"), eq("owner"), eq("tenant"),
                eq("operator-request"), eq(provider)))
                .thenReturn(registration(true));
        when(coordinator.authorize(REQUEST)).thenReturn(authorization(true, false));
        when(provider.terminate(
                "task-delivery", "owner", "tenant", "worker-delivery",
                "operator-request", REQUEST, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, true,
                        "CANCEL_REQUESTED", "receipt", null));

        RuntimeTaskClosureDTO recovered = terminate();

        assertThat(recovered.getOutcome())
                .isEqualTo(RuntimeTaskTerminationOutcome.ACCEPTED);
        verify(provider, times(1)).terminate(
                "task-delivery", "owner", "tenant", "worker-delivery",
                "operator-request", REQUEST, false);
        verify(coordinator).resultObserved(
                REQUEST, "TERMINATION_DISPATCHED");
    }

    @Test
    void responseLossRedeliveryNeverStartsSecondProviderTermination() {
        when(coordinator.accept(
                eq(REQUEST), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), eq(provider)))
                .thenReturn(registration(false), registration(true));
        when(coordinator.authorize(REQUEST))
                .thenReturn(authorization(true, false),
                        authorization(false, true));
        when(provider.terminate(
                "task-delivery", "owner", "tenant", "worker-delivery",
                "operator-request", REQUEST, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, true,
                        "CANCEL_REQUESTED", "receipt", null));
        when(audits.findSelfTaskOperation("key", "secret", REQUEST))
                .thenReturn(Optional.of(snapshot()));

        RuntimeTaskClosureDTO first = terminate();
        RuntimeTaskClosureDTO redelivery = terminate();

        assertThat(first.getOutcome())
                .isEqualTo(RuntimeTaskTerminationOutcome.ACCEPTED);
        assertThat(redelivery.getIdempotentReplay()).isTrue();
        verify(provider, times(1)).terminate(
                "task-delivery", "owner", "tenant", "worker-delivery",
                "operator-request", REQUEST, false);
    }

    @Test
    void acceptancePersistenceFailureFailsClosedBeforeProviderEffect() {
        when(coordinator.accept(
                eq(REQUEST), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), eq(provider)))
                .thenThrow(new IllegalStateException("FIXTURE_COMMIT_FAILED"));

        RuntimeTaskClosureDTO result = terminate();

        assertThat(result.getOutcome())
                .isEqualTo(RuntimeTaskTerminationOutcome.REJECTED);
        assertThat(result.getReasonCode())
                .isEqualTo("TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED");
        verify(provider, never()).terminate(
                eq("task-delivery"), eq("owner"), eq("tenant"),
                eq("worker-delivery"), anyString(), eq(REQUEST), eq(false));
    }

    private RuntimeTaskClosureDTO terminate() {
        return service.terminate(
                "key", "secret", "user", REQUEST,
                "task-delivery", "worker-delivery",
                "operator-request", "task-delivery", false);
    }

    private RuntimeRequestAuditService.TaskOperationRegistration registration(
            boolean existing) {
        return new RuntimeRequestAuditService.TaskOperationRegistration(
                new RuntimeRequestAuditService.AuditHandle(REQUEST), existing);
    }

    private RuntimeTerminationIntentPort.RuntimeTerminationAuthorization authorization(
            boolean authorized, boolean alreadyStarted) {
        var delivery = new RuntimeTerminationIntentPort.RuntimeTerminationDelivery(
                "effect-delivery", REQUEST, "task-delivery",
                "codex-biz-worker", "worker-delivery",
                "provider-task-delivery", "operation-delivery",
                "ENFORCED", "generation-delivery", "epoch-delivery",
                "JCS_SHA256_V1",
                "binding-delivery",
                alreadyStarted ? "EFFECT_STARTED" : "PREPARED");
        return new RuntimeTerminationIntentPort.RuntimeTerminationAuthorization(
                delivery, authorized, alreadyStarted, false,
                authorized ? "EFFECT_AUTHORIZED" : "EFFECT_ALREADY_STARTED");
    }

    private RuntimeTaskAuditDTO audit(String status) {
        return RuntimeTaskAuditDTO.builder()
                .taskId("task-delivery")
                .status(status)
                .terminal(false)
                .physicalWorkerId("worker-delivery")
                .taskFacts(RuntimeTaskFactsDTO.builder()
                        .taskId("task-delivery")
                        .status(status)
                        .terminal(false)
                        .physicalWorkerId("worker-delivery")
                        .taskTokenStatus("ACTIVE")
                        .activeTaskRegistrationPresent(true)
                        .build())
                .build();
    }

    private RuntimeRequestAuditService.TaskOperationSnapshot snapshot() {
        return new RuntimeRequestAuditService.TaskOperationSnapshot(
                REQUEST,
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "task-delivery",
                "user",
                true,
                "TERMINATION_REQUESTED",
                "CANCEL_REQUESTED",
                null,
                "worker-delivery",
                false);
    }
}

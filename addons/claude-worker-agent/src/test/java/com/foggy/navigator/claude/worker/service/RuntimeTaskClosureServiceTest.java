package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskClosureDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTerminationReadinessDTO;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTaskClosureServiceTest {

    private static final String REQUEST_ID = "4ee30ed0-ed15-44ec-857d-d7cd9fdcb33c";

    @Mock
    private RuntimeStateAuditService stateAuditService;
    @Mock
    private RuntimeTaskClosureProvider provider;
    @Mock
    private RuntimeRequestAuditService requestAuditService;

    private RuntimeTaskClosureService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeTaskClosureService(
                stateAuditService, List.of(provider), requestAuditService);
    }

    @Test
    void readinessIsReadOnlyAndSeparatesTaskFactsFromAuditSideEffects() {
        ownedTask(1);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.inspect("task-a", "worker-a")).thenReturn(
                new RuntimeTaskClosureProvider.TerminationReadiness(
                        true, true, true, true, true, true, null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false, "ACTIVE", true));

        RuntimeTerminationReadinessDTO result = service.readiness(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getTerminateAllowed());
        assertTrue(result.getTerminationReady());
        assertTrue(result.getTaskFacts().getModelDispatched());
        assertFalse(result.getAuditSideEffects().getModelDispatched());
        assertFalse(result.getAuditSideEffects().getTaskCreated());
        verifyNoInteractions(requestAuditService);
    }

    @Test
    void terminateDryRunDoesNotPersistRequestAuditOrDispatchTermination() {
        ownedTask(1);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "user-a", "tenant-a", "worker-a",
                "operator-stuck-task-termination", REQUEST_ID, true))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, false, false, false, "RUNNING", null, null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false, "ACTIVE", true));

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-stuck-task-termination", null, true);

        assertTrue(result.getDryRun());
        assertFalse(result.getTerminationDispatched());
        assertFalse(result.getNewTaskCreated());
        assertFalse(result.getModelRedispatched());
        verifyNoInteractions(requestAuditService);
    }

    @Test
    void reconcileDryRunDoesNotPersistRequestAuditOrChangeProjection() {
        ownedTask(1);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.reconcile(
                "task-a", "user-a", "tenant-a", "worker-a", 1, REQUEST_ID, true))
                .thenReturn(new RuntimeTaskClosureProvider.ReconciliationResult(
                        false, false, "CANCEL_REQUESTED", "WORKER_TERMINAL_ABORTED", null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCEL_REQUESTED", false, "ACTIVE", true));

        RuntimeTaskClosureDTO result = service.reconcile(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                1, null, true);

        assertTrue(result.getDryRun());
        assertFalse(result.getReconciliationChanged());
        assertEquals("WORKER_TERMINAL_ABORTED", result.getDurableEvidence());
        verifyNoInteractions(requestAuditService);
    }

    @Test
    void reconcileFailsClosedBeforeProviderCallWhenDispatchFenceMismatches() {
        ownedTask(1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.reconcile(
                        "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                        2, "task-a", false));

        assertEquals("EXPECTED_DISPATCH_COUNT_MISMATCH", error.getMessage());
        verify(provider, never()).reconcile(
                "task-a", "user-a", "tenant-a", "worker-a", 2, REQUEST_ID, false);
        verifyNoInteractions(requestAuditService);
    }

    private void ownedTask(int dispatchCount) {
        when(stateAuditService.requireOwnedTask("key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-a", "user-a", "tenant-a", "OPENAI_CODEX", "worker-a",
                        "RUNNING", false, dispatchCount));
    }

    private RuntimeTaskAuditDTO audit(
            String status, boolean terminal, String tokenStatus, boolean registrationPresent) {
        RuntimeTaskFactsDTO facts = RuntimeTaskFactsDTO.builder()
                .taskId("task-a")
                .terminal(terminal)
                .status(status)
                .physicalWorkerId("worker-a")
                .taskTokenStatus(tokenStatus)
                .activeTaskRegistrationPresent(registrationPresent)
                .dispatchCount(1)
                .retryCount(0)
                .recoveryCount(0)
                .requestedToolCount(0)
                .effectiveToolCount(0)
                .requestedFunctionCount(0)
                .effectiveFunctionCount(0)
                .taskTokenFunctionScopeEmpty(true)
                .runtimeDispatched(true)
                .modelDispatched(true)
                .businessFunctionDispatched(false)
                .build();
        return RuntimeTaskAuditDTO.builder().taskFacts(facts).build();
    }
}

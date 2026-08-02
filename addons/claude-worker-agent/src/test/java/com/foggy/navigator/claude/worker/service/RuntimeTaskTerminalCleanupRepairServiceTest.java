package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskTerminalCleanupRepairDTO;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminalCleanupRepairOutcome;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupRepairPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTaskTerminalCleanupRepairServiceTest {

    private static final String REQUEST_ID = "repair-request-1";
    private static final String READY_REASON = "NAVIGATOR_TERMINAL_REPUBLISH_READY";

    @Mock
    private RuntimeStateAuditService stateAuditService;
    @Mock
    private RuntimeRequestAuditService requestAuditService;
    @Mock
    private TerminalCleanupRepairPort terminalCleanupRepairPort;

    private RuntimeTaskTerminalCleanupRepairService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeTaskTerminalCleanupRepairService(
                stateAuditService, requestAuditService, terminalCleanupRepairPort);
        when(stateAuditService.requireOwnedTask("key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-a", "user-a", "tenant-a", "OPENAI_CODEX", "worker-a",
                        "FAILED", true, 0));
    }

    @Test
    void dryRunOnlyAssessesAndNeverMutatesCoreRepair() {
        RuntimeRequestAuditService.AuditHandle handle =
                new RuntimeRequestAuditService.AuditHandle(REQUEST_ID);
        when(requestAuditService.beginTerminalCleanupRepair(
                REQUEST_ID, "key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeRequestAuditService.TerminalCleanupRepairRegistration(
                        handle, false, receipt(false, false, "REQUEST_RECEIVED", "REQUEST_RECEIVED")));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit());
        TerminalCleanupRepairPort.TerminalCleanupRepairAssessment assessment =
                new TerminalCleanupRepairPort.TerminalCleanupRepairAssessment(
                        true, false, false, READY_REASON);
        when(terminalCleanupRepairPort.assess(
                new TerminalCleanupRepairPort.TerminalCleanupRepairAssessmentCommand(
                        "task-a", "worker-a"))).thenReturn(assessment);
        when(requestAuditService.terminalCleanupRepairDryRunCompleted(
                eq(handle), any(), eq(true), eq(READY_REASON)))
                .thenReturn(receipt(false, true, READY_REASON, "DRY_RUN_READY"));

        RuntimeTaskTerminalCleanupRepairDTO result = service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", null, true);

        assertEquals(RuntimeTaskTerminalCleanupRepairOutcome.READY, result.getOutcome());
        assertTrue(result.getRepairAllowed());
        assertFalse(result.getRepairAccepted());
        assertFalse(result.getWorkerCommandDispatched());
        assertFalse(result.getRuntimeDispatched());
        assertFalse(result.getModelDispatched());
        assertFalse(result.getBusinessFunctionDispatched());
        assertFalse(result.getTerminationTriggered());
        assertFalse(result.getRetryTriggered());
        assertFalse(result.getRecoveryTriggered());
        assertFalse(result.getNewTaskCreated());
        verify(terminalCleanupRepairPort).assess(
                new TerminalCleanupRepairPort.TerminalCleanupRepairAssessmentCommand(
                        "task-a", "worker-a"));
        verify(terminalCleanupRepairPort, never()).repair(any());
    }

    @Test
    void onlyReadySameIdDryRunCanInvokeCoreRepair() {
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt ready =
                receipt(false, true, READY_REASON, "DRY_RUN_READY");
        when(requestAuditService.findSelfTerminalCleanupRepair(
                "key", "secret", "user-a", REQUEST_ID)).thenReturn(java.util.Optional.of(ready));
        TerminalCleanupRepairPort.TerminalCleanupRepairCommand command =
                new TerminalCleanupRepairPort.TerminalCleanupRepairCommand(
                        "task-a", "worker-a", REQUEST_ID);
        when(terminalCleanupRepairPort.repair(command)).thenReturn(
                new TerminalCleanupRepairPort.TerminalCleanupRepairResult(
                        true, false, false, READY_REASON));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit(true, true, "REVOKED", false));
        when(requestAuditService.terminalCleanupRepairCompleted(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)), any(), eq(true),
                eq(READY_REASON))).thenReturn(completion(
                        receipt(true, false, READY_REASON, "REPAIRED"), false));

        RuntimeTaskTerminalCleanupRepairDTO result = service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", "task-a", false);

        assertEquals(RuntimeTaskTerminalCleanupRepairOutcome.REPAIRED, result.getOutcome());
        assertTrue(result.getRepairAccepted());
        assertFalse(result.getIdempotentReplay());
        assertTrue(result.getTerminalTombstonePresent());
        assertTrue(result.getLifecycleCleanupComplete());
        verify(terminalCleanupRepairPort).repair(command);
    }

    @Test
    void staleConcurrentConfirmReturnsLockedRepairedReceiptAsReplay() {
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt ready =
                receipt(false, true, READY_REASON, "DRY_RUN_READY");
        when(requestAuditService.findSelfTerminalCleanupRepair(
                "key", "secret", "user-a", REQUEST_ID)).thenReturn(java.util.Optional.of(ready));
        TerminalCleanupRepairPort.TerminalCleanupRepairCommand command =
                new TerminalCleanupRepairPort.TerminalCleanupRepairCommand(
                        "task-a", "worker-a", REQUEST_ID);
        when(terminalCleanupRepairPort.repair(command)).thenReturn(
                new TerminalCleanupRepairPort.TerminalCleanupRepairResult(
                        false, false, false, "LIFECYCLE_REPAIR_REQUEST_ID_MISMATCH"));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit(true, true, "REVOKED", false));
        when(requestAuditService.terminalCleanupRepairCompleted(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)), any(), eq(false),
                eq("LIFECYCLE_REPAIR_REQUEST_ID_MISMATCH"))).thenReturn(completion(
                        receipt(true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED", "REPAIRED"),
                        true));

        RuntimeTaskTerminalCleanupRepairDTO result = service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", "task-a", false);

        assertEquals(RuntimeTaskTerminalCleanupRepairOutcome.REPAIRED, result.getOutcome());
        assertTrue(result.getRepairAccepted());
        assertTrue(result.getIdempotentReplay());
        assertEquals("TERMINAL_CLEANUP_REPAIR_ACCEPTED", result.getReasonCode());
        assertTrue(result.getTerminalTombstonePresent());
        assertTrue(result.getLifecycleCleanupComplete());
        assertFalse(result.getRuntimeDispatched());
        assertFalse(result.getModelDispatched());
        assertFalse(result.getBusinessFunctionDispatched());
        assertFalse(result.getTerminationTriggered());
        assertFalse(result.getRetryTriggered());
        assertFalse(result.getRecoveryTriggered());
        verify(terminalCleanupRepairPort).repair(command);
    }

    @Test
    void completedSameIdConfirmNeverInvokesCoreRepair() {
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt repaired =
                receipt(true, false, "TERMINAL_CLEANUP_REPAIR_ACCEPTED", "REPAIRED");
        when(requestAuditService.findSelfTerminalCleanupRepair(
                "key", "secret", "user-a", REQUEST_ID)).thenReturn(java.util.Optional.of(repaired));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit(true, true, "REVOKED", false));

        RuntimeTaskTerminalCleanupRepairDTO result = service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", "task-a", false);

        assertEquals(RuntimeTaskTerminalCleanupRepairOutcome.REPAIRED, result.getOutcome());
        assertTrue(result.getRepairAccepted());
        assertTrue(result.getIdempotentReplay());
        verify(terminalCleanupRepairPort, never()).repair(any());
        verify(requestAuditService, never()).terminalCleanupRepairCompleted(
                any(), any(), anyBoolean(), anyString());
    }

    @Test
    void rejectedOrNonReadyDryRunReceiptCannotReachCoreRepair() {
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt rejected =
                receipt(true, false, "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY", "REJECTED");
        when(requestAuditService.findSelfTerminalCleanupRepair(
                "key", "secret", "user-a", REQUEST_ID)).thenReturn(java.util.Optional.of(rejected));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", "task-a", false));

        assertEquals("RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY", error.getMessage());
        verify(terminalCleanupRepairPort, never()).repair(any());
    }

    @Test
    void dryRunRejectsAbsentTokenWithoutNeverIssuedProof() {
        RuntimeRequestAuditService.AuditHandle handle =
                new RuntimeRequestAuditService.AuditHandle(REQUEST_ID);
        when(requestAuditService.beginTerminalCleanupRepair(
                REQUEST_ID, "key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeRequestAuditService.TerminalCleanupRepairRegistration(
                        handle, false, receipt(false, false,
                        "REQUEST_RECEIVED", "REQUEST_RECEIVED")));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit(true, true, "NOT_FOUND", false));
        TerminalCleanupRepairPort.TerminalCleanupRepairAssessment assessment =
                new TerminalCleanupRepairPort.TerminalCleanupRepairAssessment(
                        false, true, true, "TERMINAL_CLEANUP_ALREADY_COMPLETE");
        when(terminalCleanupRepairPort.assess(
                new TerminalCleanupRepairPort.TerminalCleanupRepairAssessmentCommand(
                        "task-a", "worker-a"))).thenReturn(assessment);
        when(requestAuditService.terminalCleanupRepairDryRunCompleted(
                eq(handle), any(), eq(false), eq("TERMINAL_CLEANUP_ALREADY_COMPLETE")))
                .thenReturn(receipt(false, false,
                        "TERMINAL_CLEANUP_ALREADY_COMPLETE", "DRY_RUN_COMPLETE"));

        RuntimeTaskTerminalCleanupRepairDTO result = service.repair(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a", null, true);

        assertEquals(RuntimeTaskTerminalCleanupRepairOutcome.REJECTED,
                result.getOutcome());
        assertEquals("NOT_FOUND", result.getTaskTokenStatus());
        assertFalse(result.getRepairAllowed());
        assertFalse(result.getRepairAccepted());
        verify(terminalCleanupRepairPort, never()).repair(any());
    }

    private RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt(
            boolean completed, boolean dryRunReady, String result, String status) {
        return new RuntimeRequestAuditService.TerminalCleanupRepairReceipt(
                REQUEST_ID, "task-a", completed, dryRunReady, result, status, null, "worker-a");
    }

    private RuntimeRequestAuditService.TerminalCleanupRepairCompletion completion(
            RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt,
            boolean idempotentReplay) {
        return new RuntimeRequestAuditService.TerminalCleanupRepairCompletion(
                receipt, idempotentReplay);
    }

    private RuntimeTaskAuditDTO audit() {
        return audit(false, false, "ACTIVE", false);
    }

    private RuntimeTaskAuditDTO audit(
            boolean tombstonePresent,
            boolean cleanupComplete,
            String taskTokenStatus,
            boolean activeTaskRegistrationPresent) {
        return RuntimeTaskAuditDTO.builder()
                .taskFacts(RuntimeTaskFactsDTO.builder()
                        .taskId("task-a")
                        .terminal(true)
                        .lifecycleCanonicalTerminal(true)
                        .terminalTombstonePresent(tombstonePresent)
                        .lifecycleCleanupComplete(cleanupComplete)
                        .status("FAILED")
                        .taskTokenStatus(taskTokenStatus)
                        .activeTaskRegistrationPresent(activeTaskRegistrationPresent)
                        .physicalWorkerId("worker-a")
                        .dispatchCount(0)
                        .retryCount(0)
                        .recoveryCount(0)
                        .runtimeDispatched(false)
                        .modelDispatched(false)
                        .businessFunctionDispatched(false)
                        .build())
                .build();
    }
}

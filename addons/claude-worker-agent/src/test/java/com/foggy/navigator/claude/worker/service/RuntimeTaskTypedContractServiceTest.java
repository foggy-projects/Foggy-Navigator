package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskClosureDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTerminationReadinessDTO;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskReconciliationState;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminationOutcome;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTerminationCapability;
import com.foggy.navigator.claude.worker.model.enums.RuntimeWorkerIdentityMatch;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTaskTypedContractServiceTest {

    private static final String REQUEST_ID = "c66ebce8-e7ae-45d2-9576-32454a960a4f";

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
        lenient().when(requestAuditService.terminationRequestReceiptEnabled())
                .thenReturn(true);
    }

    @Test
    void readinessExpressesSupportedCapabilityAndMatchedWorker() {
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.inspect("task-a", "worker-a")).thenReturn(
                new RuntimeTaskClosureProvider.TerminationReadiness(
                        true, true, true, true, true, true, null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false));

        RuntimeTerminationReadinessDTO result = service.readiness(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertEquals("task-a", result.getTaskId());
        assertEquals("worker-a", result.getExpectedPhysicalWorkerId());
        assertEquals("worker-a", result.getSelectedPhysicalWorkerId());
        assertEquals(RuntimeWorkerIdentityMatch.MATCHED, result.getWorkerIdentityMatch());
        assertEquals(RuntimeTerminationCapability.SUPPORTED, result.getTerminationCapability());
        assertEquals("RUNNING", result.getCurrentTaskStatus());
        assertFalse(result.getCanonicalTerminal());
        assertEquals("TERMINATION_READY", result.getReasonCode());
        assertTrue(result.getTerminationRequestReceiptEnabled());
    }

    @Test
    void readinessFailsClosedForUnsupportedCapabilityAndWorkerMismatch() {
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(false);
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false));

        RuntimeTerminationReadinessDTO unsupported = service.readiness(
                "key", "secret", "user-a", "task-a", "worker-a");
        RuntimeTerminationReadinessDTO mismatch = service.readiness(
                "key", "secret", "user-a", "task-a", "worker-other");
        RuntimeTerminationReadinessDTO missingExpectedWorker = service.readiness(
                "key", "secret", "user-a", "task-a", null);

        assertEquals(RuntimeTerminationCapability.UNAVAILABLE,
                unsupported.getTerminationCapability());
        assertEquals("RUNTIME_TASK_PROVIDER_UNSUPPORTED", unsupported.getReasonCode());
        assertEquals(RuntimeWorkerIdentityMatch.MISMATCHED, mismatch.getWorkerIdentityMatch());
        assertEquals("EXPECTED_PHYSICAL_WORKER_MISMATCH", mismatch.getReasonCode());
        assertFalse(mismatch.getTerminateAllowed());
        assertEquals(RuntimeWorkerIdentityMatch.UNKNOWN,
                missingExpectedWorker.getWorkerIdentityMatch());
        assertEquals("EXPECTED_PHYSICAL_WORKER_REQUIRED",
                missingExpectedWorker.getReasonCode());
        assertFalse(missingExpectedWorker.getTerminateAllowed());
        verify(provider, never()).inspect("task-a", "worker-other");
    }

    @Test
    void terminateAcceptedDoesNotPretendTaskIsTerminal() {
        newOperation();
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, true, "CANCEL_REQUESTED", "rt-1", null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false), audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertEquals(REQUEST_ID, result.getClientRequestId());
        assertEquals("task-a", result.getTaskId());
        assertEquals("CANCEL_REQUESTED", result.getCurrentTaskStatus());
        assertFalse(result.getCanonicalTerminal());
        assertEquals("TERMINATION_REQUEST_ACCEPTED", result.getReasonCode());
        assertTrue(result.getTerminationDispatched());
        assertTrue(result.getTerminationRequestReceiptPersisted());
        assertTrue(result.getRequestReconciliationAvailable());
    }

    @Test
    void acceptedWithUnobservableCanonicalFactsRemainsAcceptedAndRequiresReconciliation() {
        newOperation();
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, false,
                        "CANCEL_REQUESTED", "rt-1", null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(RuntimeTaskAuditDTO.builder().taskId("task-a").build());

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertEquals("UNKNOWN", result.getCurrentTaskStatus());
        assertNull(result.getCanonicalTerminal());
        assertTrue(result.getReconcileRequired());
        verify(requestAuditService).taskOperationCompleted(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)),
                argThat(evidence -> "TERMINATION_REQUESTED".equals(evidence.result())
                        && "CANCEL_REQUESTED".equals(evidence.taskStatus())
                        && "worker-a".equals(evidence.physicalWorkerId())),
                eq(false),
                eq(true));
    }

    @Test
    void acceptedPersistsMinimalReceiptWhenPostDispatchTaskAuditFails() {
        newOperation();
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, false,
                        "CANCEL_REQUESTED", "rt-1", null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false))
                .thenThrow(new IllegalStateException("TASK_FACTS_TEMPORARILY_UNAVAILABLE"));

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertEquals("UNKNOWN", result.getCurrentTaskStatus());
        assertNull(result.getCanonicalTerminal());
        assertTrue(result.getReconcileRequired());
        verify(requestAuditService).taskOperationCompleted(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)),
                argThat(evidence -> "TERMINATION_REQUESTED".equals(evidence.result())
                        && "CANCEL_REQUESTED".equals(evidence.taskStatus())
                        && "worker-a".equals(evidence.physicalWorkerId())),
                eq(false),
                eq(true));
    }

    @Test
    void terminateDistinguishesRejectedAndAlreadyTerminal() {
        newOperation();
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(false);
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false));

        RuntimeTaskClosureDTO rejected = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.REJECTED, rejected.getOutcome());
        assertFalse(rejected.getCanonicalTerminal());
        assertEquals("RUNTIME_TASK_PROVIDER_UNSUPPORTED", rejected.getReasonCode());

        String secondId = "547417a5-d0e1-4ac8-af78-5899ad5b2c45";
        when(requestAuditService.beginTaskOperationIdempotent(
                secondId, RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user-a", "task-a"))
                .thenReturn(new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle(secondId), false));
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", secondId, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        true, false, false, false, "CANCELLED", null, null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCELLED", true));

        RuntimeTaskClosureDTO terminal = service.terminate(
                "key", "secret", "user-a", secondId, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ALREADY_TERMINAL, terminal.getOutcome());
        assertTrue(terminal.getCanonicalTerminal());
        assertEquals("TASK_ALREADY_TERMINAL", terminal.getReasonCode());
    }

    @Test
    void duplicateAcceptedRequestReturnsIdempotentOutcomeWithoutProviderCall() {
        when(requestAuditService.beginTaskOperationIdempotent(
                REQUEST_ID, RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user-a", "task-a"))
                .thenReturn(new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle(REQUEST_ID), true));
        when(requestAuditService.findSelfTaskOperation(
                "key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "TERMINATION_REQUESTED", "CANCEL_REQUESTED", null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertTrue(result.getIdempotentReplay());
        assertFalse(result.getCanonicalTerminal());
        verifyNoInteractions(provider);
    }

    @Test
    void disabledReceiptKeepsOneShotTerminationButMakesReconciliationUnavailable() {
        when(requestAuditService.terminationRequestReceiptEnabled()).thenReturn(false);
        owned("RUNNING", false);
        when(provider.supports("OPENAI_CODEX")).thenReturn(true);
        when(provider.terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false))
                .thenReturn(new RuntimeTaskClosureProvider.TerminationResult(
                        false, true, false, true,
                        "CANCEL_REQUESTED", "rt-1", null));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("RUNNING", false), audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO termination = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);
        RuntimeTaskClosureDTO repeatedTermination = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);
        RuntimeTaskClosureDTO reconciliation = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, termination.getOutcome());
        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED,
                repeatedTermination.getOutcome());
        assertFalse(termination.getTerminationRequestReceiptEnabled());
        assertFalse(termination.getTerminationRequestReceiptPersisted());
        assertFalse(termination.getRequestReconciliationAvailable());
        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                reconciliation.getReconciliationState());
        assertEquals("TERMINATION_REQUEST_RECEIPT_DISABLED",
                reconciliation.getReasonCode());
        assertFalse(reconciliation.getTerminationRequestReceiptEnabled());
        assertFalse(reconciliation.getRequestReconciliationAvailable());
        assertFalse(reconciliation.getSameClientRequestIdReplaySafe());
        assertFalse(reconciliation.getTerminationReplayRecommended());
        verify(requestAuditService, never()).beginTaskOperationIdempotent(
                eq(REQUEST_ID), eq(RuntimeRequestAuditService.OPERATION_TASK_TERMINATE),
                eq("key"), eq("secret"), eq(null), eq("user-a"), eq("task-a"));
        verify(requestAuditService, never()).findSelfTaskOperation(
                "key", "secret", REQUEST_ID);
        verify(provider, times(2)).terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false);
    }

    @Test
    void concurrentlyRegisteredDuplicateReplaysWinningReceiptWithoutProviderCall() {
        when(requestAuditService.beginTaskOperationIdempotent(
                REQUEST_ID, RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user-a", "task-a"))
                .thenThrow(new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED"));
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "TERMINATION_REQUESTED", "CANCEL_REQUESTED", null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO result = service.terminate(
                "key", "secret", "user-a", REQUEST_ID, "task-a", "worker-a",
                "operator-request", "task-a", false);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertTrue(result.getIdempotentReplay());
        assertFalse(result.getCanonicalTerminal());
        verifyNoInteractions(provider);
    }

    @Test
    void requestReconciliationCoversNotFoundInProgressAcceptedRejectedTerminalAndAmbiguous() {
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(
                        audit("RUNNING", false),
                        audit("RUNNING", false),
                        audit("CANCEL_REQUESTED", false),
                        audit("RUNNING", false),
                        audit("CANCELLED", true),
                        audit("RUNNING", false));
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(snapshot(false, "UNKNOWN", "REQUEST_RECEIVED", null)),
                        Optional.of(snapshot(true, "TERMINATION_REQUESTED", "CANCEL_REQUESTED", null)),
                        Optional.of(snapshot(true, "FAILED", "FAILED", "TERMINATION_REJECTED")),
                        Optional.of(snapshot(true, "TERMINATION_REQUESTED", "CANCEL_REQUESTED", null)),
                        Optional.of(snapshot(true, "FUTURE_STATE", null, null)));

        assertState(RuntimeTaskReconciliationState.NOT_FOUND,
                service.reconcileTerminationRequest(
                        "key", "secret", "user-a", REQUEST_ID, "task-a"));
        assertState(RuntimeTaskReconciliationState.IN_PROGRESS,
                service.reconcileTerminationRequest(
                        "key", "secret", "user-a", REQUEST_ID, "task-a"));
        assertState(RuntimeTaskReconciliationState.ACCEPTED,
                service.reconcileTerminationRequest(
                        "key", "secret", "user-a", REQUEST_ID, "task-a"));
        assertState(RuntimeTaskReconciliationState.REJECTED,
                service.reconcileTerminationRequest(
                        "key", "secret", "user-a", REQUEST_ID, "task-a"));
        RuntimeTaskClosureDTO terminal = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");
        assertState(RuntimeTaskReconciliationState.TERMINAL, terminal);
        assertTrue(terminal.getCanonicalTerminal());
        assertState(RuntimeTaskReconciliationState.AMBIGUOUS,
                service.reconcileTerminationRequest(
                        "key", "secret", "user-a", REQUEST_ID, "task-a"));

        verify(provider, never()).terminate(
                "task-a", "owner-a", "tenant-a", "worker-a",
                "operator-request", REQUEST_ID, false);
        verify(provider, never()).reconcile(
                "task-a", "owner-a", "tenant-a", "worker-a", 1, REQUEST_ID, false);
    }

    @Test
    void requestReconciliationIsStableWhenCurrentTaskCannotBeObserved() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(true, "FUTURE_STATE", null, null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenThrow(new IllegalArgumentException("RUNTIME_TASK_NOT_FOUND"));

        RuntimeTaskClosureDTO result = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                result.getReconciliationState());
        assertEquals("UNKNOWN", result.getCurrentTaskStatus());
        assertNull(result.getCanonicalTerminal());
        assertEquals("TERMINATION_REQUEST_STATE_AMBIGUOUS", result.getReasonCode());
        assertTrue(result.getReadOnly());
        assertFalse(result.getNewClientRequestIdAllowed());
    }

    @Test
    void acceptedRequestFailsClosedAsAmbiguousAfterConvergenceTimeout() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "TERMINATION_REQUESTED", "CANCEL_REQUESTED", null, true)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO result = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                result.getReconciliationState());
        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED,
                result.getTerminationOutcome());
        assertEquals("TERMINATION_RESULT_NOT_OBSERVED_WITHIN_TIMEOUT",
                result.getReasonCode());
        assertFalse(result.getCanonicalTerminal());
        assertFalse(result.getNewClientRequestIdAllowed());
        verifyNoInteractions(provider);
    }

    @Test
    void receiptClaimCannotProduceTerminalWithoutCanonicalTaskStatus() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "ALREADY_TERMINAL", "CANCEL_REQUESTED", null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO result = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                result.getReconciliationState());
        assertFalse(result.getCanonicalTerminal());
        assertEquals("TERMINATION_RECEIPT_TERMINAL_WITHOUT_CANONICAL_TASK",
                result.getReasonCode());
    }

    @Test
    void acceptedAckAndReceiptTextCannotBecomeTerminalAuthority() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(
                        Optional.of(snapshot(
                                true, "TERMINATION_ACCEPTED", "ACKNOWLEDGED", null)),
                        Optional.of(snapshot(
                                true, "TASK_TERMINATED", "ABORTED", null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(
                        audit("CANCEL_REQUESTED", false),
                        audit("CANCEL_REQUESTED", false));

        RuntimeTaskClosureDTO acknowledged = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");
        RuntimeTaskClosureDTO receiptText = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.ACCEPTED,
                acknowledged.getReconciliationState());
        assertFalse(acknowledged.getCanonicalTerminal());
        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                receiptText.getReconciliationState());
        assertEquals("TERMINATION_EVIDENCE_WITHOUT_CANONICAL_TASK",
                receiptText.getReasonCode());
        assertFalse(receiptText.getCanonicalTerminal());
        verifyNoInteractions(provider);
    }

    @Test
    void terminalReconciliationIncludesRevokedTokenAndRemovedActiveRegistration() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "TASK_TERMINATED", "ABORTED", null)));
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(audit("CANCELLED", true));

        RuntimeTaskClosureDTO result = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.TERMINAL,
                result.getReconciliationState());
        assertTrue(result.getCanonicalTerminal());
        assertEquals("CANCELLED", result.getCurrentTaskStatus());
        assertEquals("REVOKED", result.getTaskFacts().getTaskTokenStatus());
        assertFalse(result.getTaskFacts().getActiveTaskRegistrationPresent());
        verifyNoInteractions(provider);
    }

    @Test
    void canonicalTaskDoesNotReportTerminalUntilTokenAndRegistrationCleanupConverge() {
        when(requestAuditService.findSelfTaskOperation("key", "secret", REQUEST_ID))
                .thenReturn(Optional.of(snapshot(
                        true, "TASK_TERMINATED", "ABORTED", null)));
        RuntimeTaskFactsDTO incompleteFacts = RuntimeTaskFactsDTO.builder()
                .taskId("task-a")
                .status("CANCELLED")
                .terminal(true)
                .physicalWorkerId("worker-a")
                .taskTokenStatus("ACTIVE")
                .activeTaskRegistrationPresent(false)
                .build();
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(RuntimeTaskAuditDTO.builder()
                        .taskId("task-a")
                        .status("CANCELLED")
                        .terminal(true)
                        .taskFacts(incompleteFacts)
                        .build());

        RuntimeTaskClosureDTO result = service.reconcileTerminationRequest(
                "key", "secret", "user-a", REQUEST_ID, "task-a");

        assertEquals(RuntimeTaskReconciliationState.AMBIGUOUS,
                result.getReconciliationState());
        assertTrue(result.getCanonicalTerminal());
        assertEquals("TERMINAL_CLEANUP_INCOMPLETE", result.getReasonCode());
    }

    private void newOperation() {
        when(requestAuditService.beginTaskOperationIdempotent(
                REQUEST_ID, RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "key", "secret", null, "user-a", "task-a"))
                .thenReturn(new RuntimeRequestAuditService.TaskOperationRegistration(
                        new RuntimeRequestAuditService.AuditHandle(REQUEST_ID), false));
    }

    private void owned(String status, boolean terminal) {
        when(stateAuditService.requireOwnedTask("key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-a", "owner-a", "tenant-a", "OPENAI_CODEX", "worker-a",
                        status, terminal, 1));
    }

    private RuntimeTaskAuditDTO audit(String status, boolean terminal) {
        RuntimeTaskFactsDTO facts = RuntimeTaskFactsDTO.builder()
                .taskId("task-a")
                .status(status)
                .terminal(terminal)
                .physicalWorkerId("worker-a")
                .taskTokenStatus(terminal ? "REVOKED" : "ACTIVE")
                .activeTaskRegistrationPresent(!terminal)
                .dispatchCount(1)
                .retryCount(0)
                .recoveryCount(0)
                .build();
        return RuntimeTaskAuditDTO.builder()
                .taskId("task-a")
                .status(status)
                .terminal(terminal)
                .physicalWorkerId("worker-a")
                .taskFacts(facts)
                .build();
    }

    private RuntimeRequestAuditService.TaskOperationSnapshot snapshot(
            boolean completed, String result, String status, String errorCode) {
        return snapshot(completed, result, status, errorCode, false);
    }

    private RuntimeRequestAuditService.TaskOperationSnapshot snapshot(
            boolean completed,
            String result,
            String status,
            String errorCode,
            boolean convergenceTimedOut) {
        return new RuntimeRequestAuditService.TaskOperationSnapshot(
                REQUEST_ID,
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "task-a",
                "user-a",
                completed,
                result,
                status,
                errorCode,
                "worker-a",
                convergenceTimedOut);
    }

    private void assertState(
            RuntimeTaskReconciliationState expected,
            RuntimeTaskClosureDTO actual) {
        assertEquals(expected, actual.getReconciliationState());
        assertTrue(actual.getReadOnly());
        assertTrue(actual.getTerminationRequestReceiptEnabled());
        assertTrue(actual.getRequestReconciliationAvailable());
        assertFalse(actual.getNewClientRequestIdAllowed());
    }
}

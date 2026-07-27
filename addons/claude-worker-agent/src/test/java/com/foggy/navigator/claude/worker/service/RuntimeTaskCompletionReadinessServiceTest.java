package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskCompletionReadinessDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.spi.task.RuntimeTaskCompletionReadinessProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTaskCompletionReadinessServiceTest {

    private static final String DIGEST =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private RuntimeStateAuditService stateAuditService;
    @Mock
    private RuntimeTaskCompletionReadinessProvider provider;

    private RuntimeTaskCompletionReadinessService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeTaskCompletionReadinessService(
                stateAuditService, List.of(provider));
    }

    @Test
    void authoritativeProviderReceiptAllowsOnlyCompletionReconciliationAssessment() {
        arrangeOwnedTask("codex-worker", false, 1, true);
        when(provider.supportsCompletionReadiness("codex-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(authoritativeCompletion());

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getReconciliationAssessment().getCompletionCandidate());
        assertTrue(result.getReconciliationAssessment().getTerminalEvidenceAuthoritative());
        assertTrue(result.getReconciliationAssessment().getCompletionEvidenceAuthoritative());
        assertTrue(result.getReconciliationAssessment().getCompletionReconciliationSupported());
        assertEquals("COMPLETION_RECONCILIATION_AVAILABLE",
                result.getReconciliationAssessment().getRecommendedAction());
        assertEquals(DIGEST, result.getCompletionEvidenceFacts().getFinalOutputDigest());
        assertTrue(result.getCompletionEvidenceFacts().getResultRecoverable());
        assertAllSideEffectsFalse(result.getAuditSideEffects());
    }

    @Test
    void absentProcessWithoutAuthoritativeResultNeverClaimsCompletion() {
        arrangeOwnedTask("codex-worker", false, 1, true);
        when(provider.supportsCompletionReadiness("codex-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        true, false, "UNKNOWN", false, "ABSENT",
                        false, null, null,
                        false, false, null, false, null,
                        false, null, false,
                        null, null, false, null));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getReconciliationAssessment().getWorkerProcessAbsent());
        assertTrue(result.getReconciliationAssessment().getStaleRegistrationSuspected());
        assertFalse(result.getReconciliationAssessment().getCompletionCandidate());
        assertFalse(result.getReconciliationAssessment().getTerminalEvidenceAuthoritative());
        assertFalse(result.getReconciliationAssessment().getCompletionEvidenceAuthoritative());
        assertEquals("TERMINATE_AND_RECONCILE",
                result.getReconciliationAssessment().getRecommendedAction());
        assertAllSideEffectsFalse(result.getAuditSideEffects());
    }

    @Test
    void unreachableWorkerPreservesUnknownFactsAndFailsClosed() {
        arrangeOwnedTask("codex-worker", false, 1, true);
        when(provider.supportsCompletionReadiness("codex-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        false, null, "UNKNOWN", null, "UNKNOWN",
                        null, null, null,
                        null, null, null, null, null,
                        null, null, null,
                        null, null, false, "WORKER_COMPLETION_READINESS_UNREACHABLE"));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertNull(result.getReconciliationAssessment().getWorkerProcessAbsent());
        assertFalse(result.getReconciliationAssessment().getCompletionCandidate());
        assertNull(result.getCompletionEvidenceFacts().getFinalOutputPresent());
        assertNull(result.getCompletionEvidenceFacts().getResultRecoverable());
        assertEquals("BLOCKED_INSUFFICIENT_EVIDENCE",
                result.getReconciliationAssessment().getRecommendedAction());
        assertEquals("WORKER_COMPLETION_READINESS_UNREACHABLE",
                result.getReconciliationAssessment().getAssessmentReason());
        assertEquals("WORKER_COMPLETION_READINESS_UNREACHABLE",
                result.getReconciliationAssessment().getProviderObservationErrorCode());
        assertAllSideEffectsFalse(result.getAuditSideEffects());
    }

    @Test
    void failedLanggraphReceiptCanBeAuthoritativeTerminalEvidenceWithoutClaimingCompletion() {
        arrangeOwnedTask("langgraph-biz-worker", true, 1, false, "FAILED");
        when(provider.supportsCompletionReadiness("langgraph-biz-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        true, true, "FAILED", null, "UNKNOWN",
                        false, true, "FAILED",
                        false, false, null, false, null,
                        false, null, false,
                        "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1", "task-a", true, null));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getReconciliationAssessment().getTerminalEvidenceAuthoritative());
        assertFalse(result.getReconciliationAssessment().getCompletionEvidenceAuthoritative());
        assertFalse(result.getReconciliationAssessment().getCompletionCandidate());
        assertEquals("NO_ACTION_ALREADY_TERMINAL",
                result.getReconciliationAssessment().getRecommendedAction());
        assertEquals("DURABLE_TASK+LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1+WORKER_READ_ONLY_OBSERVATION",
                result.getReconciliationAssessment().getAssessmentSource());
        assertNull(result.getReconciliationAssessment().getProviderObservationErrorCode());
        assertEquals("LANGGRAPH_PROVIDER_TASK_FAILED",
                result.getCompletionEvidenceFacts().getTerminalErrorCode());
    }

    @Test
    void completedLanggraphReceiptUsesItsExactAuthoritativeProfile() {
        arrangeOwnedTask("langgraph-biz-worker", true, 1, false, "COMPLETED");
        when(provider.supportsCompletionReadiness("langgraph-biz-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        true, true, "COMPLETED", null, "UNKNOWN",
                        false, true, "COMPLETED",
                        true, true, DIGEST, false, null,
                        true, "LANGGRAPH_BIZ_RESULT_EVENT", true,
                        "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1", "task-a", true, null));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getReconciliationAssessment().getTerminalEvidenceAuthoritative());
        assertTrue(result.getReconciliationAssessment().getCompletionEvidenceAuthoritative());
        assertEquals("DURABLE_TASK+LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1+WORKER_READ_ONLY_OBSERVATION",
                result.getReconciliationAssessment().getAssessmentSource());
    }

    @Test
    void crossProviderCompletionSignalCannotBecomeAuthoritative() {
        arrangeOwnedTask("langgraph-biz-worker", true, 1, false, "COMPLETED");
        when(provider.supportsCompletionReadiness("langgraph-biz-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        true, true, "COMPLETED", null, "UNKNOWN",
                        false, true, "COMPLETED",
                        true, true, DIGEST, false, null,
                        true, "PROVIDER_TERMINAL_EVENT", true,
                        "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1", "task-a", true, null));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertTrue(result.getReconciliationAssessment().getTerminalEvidenceAuthoritative());
        assertFalse(result.getReconciliationAssessment().getCompletionEvidenceAuthoritative());
        assertFalse(result.getReconciliationAssessment().getCompletionCandidate());
    }

    @Test
    void terminalTaskKeepsUnderlyingProviderObservationCodeSeparately() {
        arrangeOwnedTask("langgraph-biz-worker", true, 1, false, "FAILED");
        when(provider.supportsCompletionReadiness("langgraph-biz-worker")).thenReturn(true);
        when(provider.inspectCompletionReadiness("task-a", "worker-a", 1))
                .thenReturn(observation(
                        false, null, "UNKNOWN", null, "UNKNOWN",
                        null, null, null,
                        null, null, null, null, null,
                        null, null, null,
                        null, null, false, "WORKER_COMPLETION_READINESS_UNREACHABLE"));

        RuntimeTaskCompletionReadinessDTO result = service.inspect(
                "key", "secret", "user-a", "task-a", "worker-a");

        assertEquals("TASK_ALREADY_TERMINAL",
                result.getReconciliationAssessment().getAssessmentReason());
        assertEquals("WORKER_COMPLETION_READINESS_UNREACHABLE",
                result.getReconciliationAssessment().getProviderObservationErrorCode());
    }

    @Test
    void expectedWorkerMismatchStopsBeforeProviderObservation() {
        when(stateAuditService.requireOwnedTask("key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-a", "user-a", "tenant-a", "codex-worker", "worker-a",
                        "RUNNING", false, 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.inspect("key", "secret", "user-a", "task-a", "worker-other"));

        assertEquals("EXPECTED_PHYSICAL_WORKER_MISMATCH", error.getMessage());
        verify(provider, never()).inspectCompletionReadiness(
                "task-a", "worker-other", 1);
    }

    private void arrangeOwnedTask(
            String providerType, boolean terminal, int dispatchCount, boolean registrationPresent) {
        arrangeOwnedTask(providerType, terminal, dispatchCount, registrationPresent,
                terminal ? "COMPLETED" : "RUNNING");
    }

    private void arrangeOwnedTask(
            String providerType,
            boolean terminal,
            int dispatchCount,
            boolean registrationPresent,
            String status) {
        when(stateAuditService.requireOwnedTask("key", "secret", "user-a", "task-a"))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-a", "user-a", "tenant-a", providerType, "worker-a",
                        status, terminal, dispatchCount));
        RuntimeTaskFactsDTO facts = RuntimeTaskFactsDTO.builder()
                .taskId("task-a")
                .terminal(terminal)
                .status(status)
                .sanitizedErrorCode(null)
                .taskTokenStatus(terminal ? "REVOKED" : "ACTIVE")
                .activeTaskRegistrationPresent(registrationPresent)
                .dispatchCount(dispatchCount)
                .retryCount(0)
                .recoveryCount(0)
                .physicalWorkerId("worker-a")
                .modelConfigId("model-a")
                .modelVariant("codex-luna:high")
                .build();
        when(stateAuditService.auditTask("key", "secret", "user-a", "task-a"))
                .thenReturn(RuntimeTaskAuditDTO.builder().taskFacts(facts).build());
    }

    private RuntimeTaskCompletionReadinessProvider.Observation authoritativeCompletion() {
        return observation(
                true, false, "UNKNOWN", false, "ABSENT",
                false, true, "COMPLETED",
                true, true, DIGEST, false, null,
                true, "PROVIDER_TERMINAL_EVENT", true,
                "CODEX_COMPLETION_RECEIPT_V2", "task-a", true, null);
    }

    private RuntimeTaskCompletionReadinessProvider.Observation observation(
            Boolean workerReachable,
            Boolean workerTaskKnown,
            String workerTaskState,
            Boolean providerProcessPresent,
            String providerProcessState,
            Boolean providerActiveTaskPresent,
            Boolean providerTaskTerminal,
            String providerTerminalStatus,
            Boolean finalOutputPresent,
            Boolean finalOutputDurable,
            String finalOutputDigest,
            Boolean structuredOutputPresent,
            String structuredOutputDigest,
            Boolean completionSignalPresent,
            String completionSignalSource,
            Boolean resultRecoverable,
            String evidenceSchema,
            String providerTaskId,
            Boolean identityVerified,
            String errorCode) {
        String recordedAt = Boolean.TRUE.equals(completionSignalPresent)
                ? "2026-07-25T12:00:00Z" : null;
        boolean terminalSignalPresent = Boolean.TRUE.equals(providerTaskTerminal);
        String terminalSignalSource = terminalSignalPresent
                ? ("LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1".equals(evidenceSchema)
                ? ("COMPLETED".equals(providerTerminalStatus)
                ? "LANGGRAPH_BIZ_RESULT_EVENT" : "LANGGRAPH_BIZ_ERROR_EVENT")
                : "PROVIDER_TERMINAL_EVENT")
                : null;
        String terminalSignalRecordedAt = terminalSignalPresent
                ? "2026-07-25T12:00:00Z" : null;
        return new RuntimeTaskCompletionReadinessProvider.Observation(
                workerReachable,
                "2026-07-25T12:01:00Z",
                workerTaskKnown,
                workerTaskState,
                providerProcessPresent,
                providerProcessState,
                providerActiveTaskPresent,
                providerTaskTerminal,
                providerTerminalStatus,
                null,
                null,
                null,
                finalOutputPresent,
                finalOutputDurable,
                finalOutputDigest,
                recordedAt,
                structuredOutputPresent,
                structuredOutputDigest,
                terminalSignalPresent,
                terminalSignalSource,
                terminalSignalRecordedAt,
                completionSignalPresent,
                completionSignalSource,
                recordedAt,
                resultRecoverable,
                evidenceSchema,
                providerTaskId,
                evidenceSchema == null ? null : 1,
                identityVerified,
                "FAILED".equals(providerTerminalStatus)
                        ? "LANGGRAPH_PROVIDER_TASK_FAILED" : null,
                errorCode);
    }

    private void assertAllSideEffectsFalse(RuntimeAuditSideEffectsDTO effects) {
        assertFalse(effects.getAccessTokenIssued());
        assertFalse(effects.getRuntimeTokenIssued());
        assertFalse(effects.getTaskTokenIssued());
        assertFalse(effects.getTaskCreated());
        assertFalse(effects.getContextCreated());
        assertFalse(effects.getSessionCreated());
        assertFalse(effects.getWorkerCommandDispatched());
        assertFalse(effects.getModelDispatched());
        assertFalse(effects.getBusinessFunctionDispatched());
        assertFalse(effects.getRetryTriggered());
        assertFalse(effects.getRecoveryTriggered());
        assertFalse(effects.getTerminationTriggered());
        assertFalse(effects.getReconciliationTriggered());
        assertFalse(effects.getProvisioningResourceChanged());
    }
}

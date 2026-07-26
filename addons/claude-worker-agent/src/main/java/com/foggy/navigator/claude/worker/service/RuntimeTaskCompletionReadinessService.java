package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeCompletionEvidenceFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeCompletionReconciliationAssessmentDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskCompletionReadinessDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeWorkerObservedFactsDTO;
import com.foggy.navigator.spi.task.RuntimeTaskCompletionReadinessProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeTaskCompletionReadinessService {

    private static final String V2_SCHEMA = "CODEX_COMPLETION_RECEIPT_V2";
    private static final String PROVIDER_TERMINAL_SOURCE = "PROVIDER_TERMINAL_EVENT";
    private static final String SHA256_DIGEST = "sha256:[a-f0-9]{64}";

    private final RuntimeStateAuditService stateAuditService;
    private final List<RuntimeTaskCompletionReadinessProvider> providers;

    public RuntimeTaskCompletionReadinessDTO inspect(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId,
            String expectedPhysicalWorkerId) {
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        requireExpectedWorker(owned, expectedPhysicalWorkerId);
        RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                appKey, appSecret, upstreamUserId, taskId);
        RuntimeTaskFactsDTO taskFacts = audit.getTaskFacts();

        RuntimeTaskCompletionReadinessProvider provider = providers.stream()
                .filter(value -> value.supportsCompletionReadiness(owned.providerType()))
                .findFirst()
                .orElse(null);
        RuntimeTaskCompletionReadinessProvider.Observation observed = provider == null
                ? unsupportedObservation()
                : provider.inspectCompletionReadiness(
                        taskId, expectedPhysicalWorkerId, owned.dispatchCount());

        RuntimeWorkerObservedFactsDTO workerFacts = RuntimeWorkerObservedFactsDTO.builder()
                .workerReachable(observed.workerReachable())
                .workerObservedAt(parseTime(observed.workerObservedAt()))
                .workerTaskKnown(observed.workerTaskKnown())
                .workerTaskState(unknown(observed.workerTaskState()))
                .providerProcessPresent(observed.providerProcessPresent())
                .providerProcessState(unknown(observed.providerProcessState()))
                .providerActiveTaskPresent(observed.providerActiveTaskPresent())
                .providerTaskTerminal(observed.providerTaskTerminal())
                .providerTerminalStatus(observed.providerTerminalStatus())
                .lastHeartbeatAt(parseTime(observed.lastHeartbeatAt()))
                .lastProgressAt(parseTime(observed.lastProgressAt()))
                .processExitedAt(parseTime(observed.processExitedAt()))
                .build();
        RuntimeCompletionEvidenceFactsDTO evidenceFacts =
                RuntimeCompletionEvidenceFactsDTO.builder()
                        .finalOutputPresent(observed.finalOutputPresent())
                        .finalOutputDurable(observed.finalOutputDurable())
                        .finalOutputDigest(observed.finalOutputDigest())
                        .finalOutputRecordedAt(parseTime(observed.finalOutputRecordedAt()))
                        .structuredOutputPresent(observed.structuredOutputPresent())
                        .structuredOutputDigest(observed.structuredOutputDigest())
                        .completionSignalPresent(observed.completionSignalPresent())
                        .completionSignalSource(observed.completionSignalSource())
                        .completionSignalRecordedAt(parseTime(observed.completionSignalRecordedAt()))
                        .resultRecoverable(observed.resultRecoverable())
                        .build();

        RuntimeCompletionReconciliationAssessmentDTO assessment =
                assess(owned, taskFacts, observed);
        return RuntimeTaskCompletionReadinessDTO.builder()
                .taskFacts(taskFacts)
                .workerObservedFacts(workerFacts)
                .completionEvidenceFacts(evidenceFacts)
                .reconciliationAssessment(assessment)
                .auditSideEffects(noSideEffects())
                .build();
    }

    private RuntimeCompletionReconciliationAssessmentDTO assess(
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTaskFactsDTO taskFacts,
            RuntimeTaskCompletionReadinessProvider.Observation observed) {
        Boolean processAbsent = "ABSENT".equals(observed.providerProcessState())
                && Boolean.FALSE.equals(observed.providerProcessPresent())
                ? Boolean.TRUE
                : "PRESENT".equals(observed.providerProcessState())
                && Boolean.TRUE.equals(observed.providerProcessPresent())
                ? Boolean.FALSE
                : null;
        boolean registrationPresent = Boolean.TRUE.equals(
                taskFacts.getActiveTaskRegistrationPresent());
        boolean staleRegistration = !owned.terminal() && registrationPresent
                && (Boolean.FALSE.equals(observed.workerTaskKnown())
                || Boolean.TRUE.equals(processAbsent));
        boolean digestValid = StringUtils.hasText(observed.finalOutputDigest())
                && observed.finalOutputDigest().matches(SHA256_DIGEST);
        boolean dispatchMatches = observed.receiptDispatchCount() != null
                && observed.receiptDispatchCount() == owned.dispatchCount();
        boolean authoritative = Boolean.TRUE.equals(observed.identityVerified())
                && V2_SCHEMA.equals(observed.evidenceSchema())
                && dispatchMatches
                && Boolean.TRUE.equals(observed.providerTaskTerminal())
                && "COMPLETED".equals(observed.providerTerminalStatus())
                && Boolean.TRUE.equals(observed.completionSignalPresent())
                && PROVIDER_TERMINAL_SOURCE.equals(observed.completionSignalSource())
                && parseTime(observed.completionSignalRecordedAt()) != null
                && Boolean.TRUE.equals(observed.finalOutputPresent())
                && Boolean.TRUE.equals(observed.finalOutputDurable())
                && digestValid
                && Boolean.TRUE.equals(observed.resultRecoverable());
        boolean completionCandidate = authoritative;
        boolean completionReconciliationSupported = !owned.terminal() && authoritative;
        boolean terminationReconciliationSupported = !owned.terminal()
                && Boolean.TRUE.equals(observed.workerReachable())
                && Boolean.TRUE.equals(processAbsent);
        boolean reconcileRequired = !owned.terminal()
                && (completionReconciliationSupported || Boolean.TRUE.equals(processAbsent));

        String recommendedAction;
        String reason;
        if (owned.terminal()) {
            recommendedAction = "NO_ACTION_ALREADY_TERMINAL";
            reason = "TASK_ALREADY_TERMINAL";
        } else if (completionReconciliationSupported) {
            recommendedAction = "COMPLETION_RECONCILIATION_AVAILABLE";
            reason = "AUTHORITATIVE_DURABLE_COMPLETION_EVIDENCE";
        } else if (Boolean.TRUE.equals(processAbsent)) {
            recommendedAction = terminationReconciliationSupported
                    ? "TERMINATE_AND_RECONCILE"
                    : "OPERATOR_REVIEW";
            reason = "PROVIDER_PROCESS_ABSENT_NO_AUTHORITATIVE_COMPLETION";
        } else if (Boolean.TRUE.equals(observed.providerProcessPresent())) {
            recommendedAction = "CONTINUE_OBSERVING";
            reason = "PROVIDER_PROCESS_PRESENT";
        } else {
            recommendedAction = "BLOCKED_INSUFFICIENT_EVIDENCE";
            reason = observed.sanitizedErrorCode() != null
                    ? observed.sanitizedErrorCode()
                    : "PROVIDER_STATE_UNKNOWN";
        }

        String source = providerSource(observed, authoritative);
        return RuntimeCompletionReconciliationAssessmentDTO.builder()
                .staleRegistrationSuspected(staleRegistration)
                .workerProcessAbsent(processAbsent)
                .completionCandidate(completionCandidate)
                .completionEvidenceAuthoritative(authoritative)
                .completionReconciliationSupported(completionReconciliationSupported)
                .terminationReconciliationSupported(terminationReconciliationSupported)
                .reconcileRequired(reconcileRequired)
                .reconcileReason(reason)
                .recommendedAction(recommendedAction)
                .assessmentReason(reason)
                .assessmentSource(source)
                .assessedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private String providerSource(
            RuntimeTaskCompletionReadinessProvider.Observation observed,
            boolean authoritative) {
        if (authoritative) {
            return "DURABLE_TASK+CODEX_COMPLETION_RECEIPT_V2+WORKER_PROCESS_SNAPSHOT";
        }
        if (Boolean.TRUE.equals(observed.workerReachable())) {
            return "DURABLE_TASK+WORKER_READ_ONLY_OBSERVATION";
        }
        if (Boolean.FALSE.equals(observed.workerReachable())) {
            return "DURABLE_TASK+WORKER_UNREACHABLE";
        }
        return "DURABLE_TASK+PROVIDER_UNSUPPORTED";
    }

    private RuntimeTaskCompletionReadinessProvider.Observation unsupportedObservation() {
        return new RuntimeTaskCompletionReadinessProvider.Observation(
                null, null, null, "UNKNOWN", null, "UNKNOWN",
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                false, "RUNTIME_COMPLETION_READINESS_UNSUPPORTED");
    }

    private void requireExpectedWorker(
            RuntimeStateAuditService.OwnedRuntimeTask task, String expectedPhysicalWorkerId) {
        if (!StringUtils.hasText(expectedPhysicalWorkerId)
                || !expectedPhysicalWorkerId.trim().equals(task.physicalWorkerId())) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }
    }

    private OffsetDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String unknown(String value) {
        return StringUtils.hasText(value) ? value : "UNKNOWN";
    }

    private RuntimeAuditSideEffectsDTO noSideEffects() {
        return RuntimeAuditSideEffectsDTO.builder()
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .workerCommandDispatched(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .retryTriggered(false)
                .recoveryTriggered(false)
                .terminationTriggered(false)
                .reconciliationTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }
}

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
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeTaskCompletionReadinessService {

    private static final String CODEX_V2_SCHEMA = "CODEX_COMPLETION_RECEIPT_V2";
    private static final String LANGGRAPH_V1_SCHEMA = "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1";
    private static final Set<String> SUPPORTED_SCHEMAS = Set.of(CODEX_V2_SCHEMA, LANGGRAPH_V1_SCHEMA);
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "ABORTED");
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
                        .terminalSignalPresent(observed.terminalSignalPresent())
                        .terminalSignalSource(observed.terminalSignalSource())
                        .terminalSignalRecordedAt(parseTime(observed.terminalSignalRecordedAt()))
                        .terminalErrorCode(observed.terminalErrorCode())
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
        boolean terminalAuthoritative = Boolean.TRUE.equals(observed.identityVerified())
                && SUPPORTED_SCHEMAS.contains(observed.evidenceSchema())
                && dispatchMatches
                && Boolean.TRUE.equals(observed.providerTaskTerminal())
                && TERMINAL_STATUSES.contains(observed.providerTerminalStatus())
                && Boolean.TRUE.equals(observed.terminalSignalPresent())
                && terminalSignalMatchesProviderProfile(observed)
                && parseTime(observed.terminalSignalRecordedAt()) != null
                && !StringUtils.hasText(observed.sanitizedErrorCode());
        boolean completionAuthoritative = terminalAuthoritative
                && "COMPLETED".equals(observed.providerTerminalStatus())
                && Boolean.TRUE.equals(observed.completionSignalPresent())
                && completionSignalMatchesProviderProfile(observed)
                && parseTime(observed.completionSignalRecordedAt()) != null
                && Boolean.TRUE.equals(observed.finalOutputPresent())
                && Boolean.TRUE.equals(observed.finalOutputDurable())
                && digestValid
                && Boolean.TRUE.equals(observed.resultRecoverable());
        boolean completionCandidate = completionAuthoritative;
        boolean completionReconciliationSupported = !owned.terminal() && completionAuthoritative;
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

        String source = providerSource(observed, terminalAuthoritative, completionAuthoritative);
        return RuntimeCompletionReconciliationAssessmentDTO.builder()
                .staleRegistrationSuspected(staleRegistration)
                .workerProcessAbsent(processAbsent)
                .completionCandidate(completionCandidate)
                .terminalEvidenceAuthoritative(terminalAuthoritative)
                .completionEvidenceAuthoritative(completionAuthoritative)
                .completionReconciliationSupported(completionReconciliationSupported)
                .terminationReconciliationSupported(terminationReconciliationSupported)
                .reconcileRequired(reconcileRequired)
                .reconcileReason(reason)
                .recommendedAction(recommendedAction)
                .assessmentReason(reason)
                .assessmentSource(source)
                .providerObservationErrorCode(observed.sanitizedErrorCode())
                .assessedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private boolean terminalSignalMatchesProviderProfile(
            RuntimeTaskCompletionReadinessProvider.Observation observed) {
        if (CODEX_V2_SCHEMA.equals(observed.evidenceSchema())) {
            return "PROVIDER_TERMINAL_EVENT".equals(observed.terminalSignalSource());
        }
        if (LANGGRAPH_V1_SCHEMA.equals(observed.evidenceSchema())) {
            String expected = "COMPLETED".equals(observed.providerTerminalStatus())
                    ? "LANGGRAPH_BIZ_RESULT_EVENT"
                    : "LANGGRAPH_BIZ_ERROR_EVENT";
            return expected.equals(observed.terminalSignalSource());
        }
        return false;
    }

    private boolean completionSignalMatchesProviderProfile(
            RuntimeTaskCompletionReadinessProvider.Observation observed) {
        if (CODEX_V2_SCHEMA.equals(observed.evidenceSchema())) {
            return "PROVIDER_TERMINAL_EVENT".equals(observed.completionSignalSource());
        }
        return LANGGRAPH_V1_SCHEMA.equals(observed.evidenceSchema())
                && "LANGGRAPH_BIZ_RESULT_EVENT".equals(observed.completionSignalSource());
    }

    private String providerSource(
            RuntimeTaskCompletionReadinessProvider.Observation observed,
            boolean terminalAuthoritative,
            boolean completionAuthoritative) {
        if (completionAuthoritative && CODEX_V2_SCHEMA.equals(observed.evidenceSchema())) {
            return "DURABLE_TASK+CODEX_COMPLETION_RECEIPT_V2+WORKER_PROCESS_SNAPSHOT";
        }
        if (terminalAuthoritative) {
            return "DURABLE_TASK+" + observed.evidenceSchema() + "+WORKER_READ_ONLY_OBSERVATION";
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
                null, null, null, null, null, null,
                null, null, null, null,
                false, null, "RUNTIME_COMPLETION_READINESS_UNSUPPORTED");
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

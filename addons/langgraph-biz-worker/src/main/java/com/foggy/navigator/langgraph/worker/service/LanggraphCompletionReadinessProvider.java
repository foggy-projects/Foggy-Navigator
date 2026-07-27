package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.spi.task.RuntimeTaskCompletionReadinessProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral adapter over LangGraph Biz Worker's content-free receipt route. */
@Service
@RequiredArgsConstructor
public class LanggraphCompletionReadinessProvider implements RuntimeTaskCompletionReadinessProvider {

    private static final String RECEIPT_SCHEMA = "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1";

    private final LanggraphTaskRepository taskRepository;
    private final SessionTaskRepository sessionTaskRepository;
    private final LanggraphWorkerService workerService;

    @Override
    public boolean supportsCompletionReadiness(String providerType) {
        return LanggraphTaskService.PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public Observation inspectCompletionReadiness(
            String taskId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount) {
        LanggraphTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            return unavailable(false, "RUNTIME_TASK_NOT_FOUND");
        }
        if (!Objects.equals(task.getWorkerId(), expectedPhysicalWorkerId)) {
            return unavailable(false, "EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }

        SessionTaskEntity durableTask = sessionTaskRepository.findByTaskId(taskId).orElse(null);
        Map<String, Object> durableEvidence = durableEvidence(durableTask);
        try {
            LanggraphWorkerEntity worker = workerService.getWorkerEntity(expectedPhysicalWorkerId);
            Map<String, Object> observed = workerService.createClient(worker)
                    .getTaskCompletionReadiness(taskId)
                    .block(Duration.ofSeconds(10));
            if (observed == null) {
                return unavailable(false, "WORKER_COMPLETION_READINESS_EMPTY");
            }

            String responseWorkerId = stringValue(observed.get("receipt_worker_id"));
            String responseTaskId = stringValue(observed.get("receipt_task_id"));
            String providerTaskId = stringValue(observed.get("provider_task_id"));
            Integer receiptDispatchCount = integerValue(observed.get("receipt_dispatch_count"));
            String terminalStatus = stringValue(observed.get("provider_terminal_status"));
            String evidenceSchema = stringValue(observed.get("evidence_schema"));
            boolean conflict = Boolean.TRUE.equals(booleanObject(observed.get("evidence_conflict")));
            boolean identityVerified = !conflict
                    && RECEIPT_SCHEMA.equals(evidenceSchema)
                    && Objects.equals(expectedPhysicalWorkerId, responseWorkerId)
                    && Objects.equals(taskId, responseTaskId)
                    && Objects.equals(taskId, providerTaskId)
                    && receiptDispatchCount != null
                    && receiptDispatchCount == expectedDispatchCount
                    && terminalStatusMatches(task.getStatus(), terminalStatus);

            String workerDigest = stringValue(observed.get("final_output_digest"));
            String durableDigest = stringValue(durableEvidence.get("finalOutputDigest"));
            Boolean workerOutputPresent = booleanObject(observed.get("final_output_present"));
            Boolean durableOutputPresent = booleanObject(durableEvidence.get("finalOutputPresent"));
            boolean digestMatches = !Boolean.TRUE.equals(workerOutputPresent)
                    || (Objects.equals(workerDigest, durableDigest)
                    && Boolean.TRUE.equals(durableOutputPresent));

            String workerStructuredDigest = stringValue(observed.get("structured_output_digest"));
            String durableStructuredDigest = stringValue(durableEvidence.get("structuredOutputDigest"));
            Boolean workerStructuredPresent = booleanObject(observed.get("structured_output_present"));
            boolean structuredDigestMatches = !Boolean.TRUE.equals(workerStructuredPresent)
                    || Objects.equals(workerStructuredDigest, durableStructuredDigest);

            String observationError = stringValue(observed.get("sanitized_error_code"));
            if (conflict) {
                observationError = "LANGGRAPH_COMPLETION_EVIDENCE_CONFLICT";
            } else if (Boolean.TRUE.equals(booleanObject(observed.get("provider_task_terminal")))
                    && !identityVerified) {
                observationError = "WORKER_COMPLETION_EVIDENCE_IDENTITY_MISMATCH";
            } else if (!digestMatches || !structuredDigestMatches) {
                observationError = "WORKER_COMPLETION_EVIDENCE_DIGEST_MISMATCH";
            }

            boolean durableResult = Boolean.TRUE.equals(durableEvidence.get("finalOutputDurable"));
            boolean resultRecoverable = digestMatches
                    && Boolean.TRUE.equals(durableEvidence.get("resultRecoverable"));
            return new Observation(
                    true,
                    stringValue(observed.get("worker_observed_at")),
                    booleanObject(observed.get("worker_task_known")),
                    stringValue(observed.get("worker_task_state")),
                    booleanObject(observed.get("provider_process_present")),
                    stringValue(observed.get("provider_process_state")),
                    booleanObject(observed.get("provider_active_task_present")),
                    booleanObject(observed.get("provider_task_terminal")),
                    terminalStatus,
                    null,
                    null,
                    null,
                    Boolean.TRUE.equals(workerOutputPresent) && Boolean.TRUE.equals(durableOutputPresent),
                    durableResult,
                    digestMatches ? workerDigest : null,
                    stringValue(durableEvidence.get("finalOutputRecordedAt")),
                    Boolean.TRUE.equals(workerStructuredPresent)
                            && Boolean.TRUE.equals(durableEvidence.get("structuredOutputPresent")),
                    structuredDigestMatches ? workerStructuredDigest : null,
                    booleanObject(observed.get("terminal_signal_present")),
                    stringValue(observed.get("terminal_signal_source")),
                    stringValue(observed.get("terminal_signal_recorded_at")),
                    booleanObject(observed.get("completion_signal_present")),
                    stringValue(observed.get("completion_signal_source")),
                    stringValue(observed.get("completion_signal_recorded_at")),
                    resultRecoverable,
                    evidenceSchema,
                    providerTaskId,
                    receiptDispatchCount,
                    identityVerified,
                    stringValue(observed.get("terminal_error_code")),
                    observationError);
        } catch (WebClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                return unavailable(true, "WORKER_COMPLETION_READINESS_UNSUPPORTED");
            }
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
                return unavailable(true, "WORKER_COMPLETION_READINESS_AUTH_FAILED");
            }
            return unavailable(true, "WORKER_COMPLETION_EVIDENCE_UNAVAILABLE");
        } catch (RuntimeException error) {
            return unavailable(false, "WORKER_COMPLETION_READINESS_UNREACHABLE");
        }
    }

    private Map<String, Object> durableEvidence(SessionTaskEntity task) {
        if (task == null) {
            return Map.of();
        }
        Object value = ProviderStateCodec.parseObject(task.getTaskStateJson())
                .get(LanggraphTaskService.COMPLETION_EVIDENCE_STATE_KEY);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
        }
        return Map.of();
    }

    private boolean terminalStatusMatches(String durableStatus, String workerStatus) {
        if (!StringUtils.hasText(durableStatus) || !StringUtils.hasText(workerStatus)) {
            return false;
        }
        if (durableStatus.equals(workerStatus)) {
            return true;
        }
        return "ABORTED".equals(durableStatus) && "CANCELLED".equals(workerStatus);
    }

    private Observation unavailable(Boolean workerReachable, String errorCode) {
        return new Observation(
                workerReachable, null, null, "UNKNOWN", null, "UNKNOWN",
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                false, null, errorCode);
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text.trim());
        }
        return null;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

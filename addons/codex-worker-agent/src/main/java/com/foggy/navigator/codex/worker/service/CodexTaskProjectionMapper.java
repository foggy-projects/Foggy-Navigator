package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.util.TaskResponseTimeoutSupport;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Pure projection of an already-resolved Codex task view. */
final class CodexTaskProjectionMapper {

    /**
     * {@code observedAt} is captured once by the caller so both timeout fields describe the same
     * observation. Identity, provider, context and diagnostics are also caller-owned resolutions;
     * this mapper never falls back to provider or persistence state for them.
     */
    DispatchTaskDTO toDispatchTask(CodexTaskEntity entity,
                                   String logicalAgentId,
                                   String providerType,
                                   String contextId,
                                   ErrorEnvelope error,
                                   LocalDateTime observedAt) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(observedAt, "observedAt");

        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .runtimeId(entity.getRuntimeId())
                .runtimeRevision(entity.getRuntimeRevision())
                .runtimeType(entity.getRuntimeType())
                .runtimeInstanceId(entity.getRuntimeInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .runtimeAcceptanceState(entity.getRuntimeAcceptanceState())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(logicalAgentId)
                .providerType(providerType)
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .directoryId(entity.getDirectoryId())
                .status(entity.getStatus())
                .model(entity.getModel())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .resultText(entity.getResultText())
                .errorMessage(entity.getErrorMessage())
                .error(toErrorMap(error))
                .lastAckedSeq(entity.getLastAckedSeq())
                .lastOutputAt(entity.getLastOutputAt())
                .responseTimedOut(TaskResponseTimeoutSupport.isResponseTimedOut(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), observedAt))
                .silentForSeconds(TaskResponseTimeoutSupport.silentForSeconds(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), observedAt))
                .responseTimeoutThresholdSeconds(TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS)
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .createdAtEpochMs(entity.getCreatedAtEpochMs())
                .updatedAt(entity.getUpdatedAt())
                .codexThreadId(entity.getCodexThreadId())
                .contextId(contextId)
                .build();
    }

    String interactionState(String taskStatus) {
        if ("RUNNING".equals(taskStatus) || "PENDING".equals(taskStatus)
                || "CANCEL_REQUESTED".equals(taskStatus)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus)
                || "ABORTED".equals(taskStatus) || "AWAITING_PERMISSION".equals(taskStatus)
                || "AWAITING_INPUT".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private Map<String, Object> toErrorMap(ErrorEnvelope error) {
        if (error == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("errorCode", error.getErrorCode());
        value.put("message", error.getMessage());
        value.put("category", error.getCategory());
        value.put("runtimePhase", error.getRuntimePhase());
        value.put("recoverable", error.getRecoverable());
        value.put("diagnosticRef", error.getDiagnosticRef());
        value.put("occurredAt", error.getOccurredAt());
        value.put("taskId", error.getTaskId());
        value.put("providerType", error.getProviderType());
        value.put("runtimeType", error.getRuntimeType());
        value.values().removeIf(Objects::isNull);
        return value;
    }
}

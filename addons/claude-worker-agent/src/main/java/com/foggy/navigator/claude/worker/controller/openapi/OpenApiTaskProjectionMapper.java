package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.support.BusinessAgentSessionMessageVisibility;
import com.foggy.navigator.claude.worker.model.dto.OpenApiTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskArtifactRefDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskCancelCapabilityDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskCorrelationDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskDiagnosticsDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskEvidenceDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskFinalAnswerDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskReportRefDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskStructuredOutputDTO;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.util.ProviderRouteRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stateless projection of caller-preloaded Task and message facts. */
final class OpenApiTaskProjectionMapper {

    private static final String BACKEND_OPENAI_CODEX = ProviderRouteRegistry.BACKEND_OPENAI_CODEX;
    private static final String BACKEND_OPENAI_CODEX_APP_SERVER =
            ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER;
    private static final int MAX_STRUCTURED_OUTPUT_CONTENT_LENGTH = 64 * 1024;

    TaskStatusProjection projectStatus(String rawStatus) {
        String responseStatus = mapTaskStatus(rawStatus);
        return new TaskStatusProjection(
                responseStatus,
                OpenApiProjectionSupport.hasText(rawStatus) ? responseStatus : null,
                terminalStatusFromTaskStatus(responseStatus));
    }

    String mapTaskStatus(String internalStatus) {
        if (internalStatus == null) {
            return "UNKNOWN";
        }
        return switch (internalStatus) {
            case "PENDING" -> "SUBMITTED";
            case "RUNNING" -> "RUNNING";
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "ABORTED" -> "CANCELLED";
            case "AWAITING_PERMISSION" -> "AWAITING_INPUT";
            default -> internalStatus;
        };
    }

    String mapA2aState(A2aTaskState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state) {
            case SUBMITTED -> "SUBMITTED";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELLED";
        };
    }

    String terminalStatusFromTaskStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "CANCELLED", "CANCELED" -> "CANCELLED";
            default -> null;
        };
    }

    OpenApiTaskDTO mapA2aTask(
            ObjectMapper objectMapper,
            A2aTask task,
            String agentId,
            SessionTaskEntity taskEntity) {
        OpenApiTaskDTO.OpenApiTaskDTOBuilder builder = OpenApiTaskDTO.builder()
                .taskId(task.getId())
                .agentId(agentId)
                .contextId(task.getContextId());

        String status = null;
        if (task.getStatus() != null) {
            status = mapA2aState(task.getStatus().getState());
            builder.status(status);
            if (task.getStatus().getState() == A2aTaskState.FAILED) {
                builder.errorMessage(OpenApiProjectionSupport.sanitizeDiagnosticText(
                        task.getStatus().getDescription()));
            }
        }

        if (task.getArtifacts() != null) {
            for (A2aArtifact artifact : task.getArtifacts()) {
                if (artifact.getParts() == null) {
                    continue;
                }
                for (A2aPart part : artifact.getParts()) {
                    if ("text".equals(part.getType()) && part.getText() != null) {
                        builder.result(part.getText());
                        break;
                    }
                }
            }
        }

        Map<String, Object> metadata = task.getMetadata();
        if (metadata != null) {
            Object workerTaskId = metadata.get("workerTaskId");
            if (workerTaskId instanceof String value && OpenApiProjectionSupport.hasText(value)) {
                builder.workerTaskId(value).providerTaskId(value);
            }
            Object lastAckedSeq = metadata.get("lastAckedSeq");
            if (lastAckedSeq instanceof Number value) {
                builder.lastAckedSeq(value.intValue());
            }
            Object durationMs = metadata.get("durationMs");
            if (durationMs instanceof Number value) {
                builder.durationMs(value.longValue());
            }
            Object costUsd = metadata.get("costUsd");
            if (costUsd instanceof BigDecimal value) {
                builder.costUsd(value);
            } else if (costUsd instanceof Number value) {
                builder.costUsd(BigDecimal.valueOf(value.doubleValue()));
            }
            builder.modelConfigId(OpenApiProjectionSupport.stringValue(metadata.get("modelConfigId")))
                    .modelConfigSource(OpenApiProjectionSupport.stringValue(metadata.get("modelConfigSource")))
                    .workerBackend(OpenApiProjectionSupport.stringValue(metadata.get("workerBackend")))
                    .providerType(OpenApiProjectionSupport.stringValue(metadata.get("providerType")))
                    .taskSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(metadata.get("taskSource")),
                            OpenApiProjectionSupport.stringValue(metadata.get("source"))))
                    .workerSource(OpenApiProjectionSupport.stringValue(metadata.get("workerSource")))
                    .backendSource(OpenApiProjectionSupport.stringValue(metadata.get("backendSource")))
                    .effectiveToolCount(OpenApiProjectionSupport.integerValue(metadata.get("effectiveToolCount")))
                    .effectiveFunctionCount(OpenApiProjectionSupport.integerValue(
                            metadata.get("effectiveFunctionCount")))
                    .toolScopeSource(OpenApiProjectionSupport.stringValue(metadata.get("toolScopeSource")))
                    .toolScopeKind(OpenApiProjectionSupport.stringValue(metadata.get("toolScopeKind")))
                    .functionScopeSource(OpenApiProjectionSupport.stringValue(metadata.get("functionScopeSource")))
                    .taskTokenFunctionScopeEmpty(OpenApiProjectionSupport.booleanValue(
                            metadata.get("taskTokenFunctionScopeEmpty")))
                    .runtimeDispatched(OpenApiProjectionSupport.booleanValue(metadata.get("runtimeDispatched")))
                    .taskTokenStatus(OpenApiProjectionSupport.stringValue(metadata.get("taskTokenStatus")));
        }

        if (taskEntity != null) {
            Map<String, Object> taskState = parseTaskState(objectMapper, taskEntity.getTaskStateJson());
            builder.providerType(taskEntity.getProviderType())
                    .modelConfigId(OpenApiProjectionSupport.firstNonBlank(
                            taskEntity.getModelConfigId(),
                            OpenApiProjectionSupport.stringValue(taskState.get("modelConfigId"))))
                    .modelConfigSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("modelConfigSource")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("modelConfigSource") : null)))
                    .workerBackend(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("workerBackend")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("workerBackend") : null),
                            workerBackendFromProviderType(taskEntity.getProviderType())))
                    .taskSource(OpenApiProjectionSupport.firstNonBlank(
                            taskEntity.getSource(),
                            OpenApiProjectionSupport.stringValue(taskState.get("taskSource"))))
                    .workerSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("workerSource")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("workerSource") : null)))
                    .backendSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("backendSource")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("backendSource") : null)))
                    .effectiveToolCount(OpenApiProjectionSupport.firstNonNull(
                            OpenApiProjectionSupport.integerValue(taskState.get("effectiveToolCount")),
                            OpenApiProjectionSupport.integerValue(metadata != null
                                    ? metadata.get("effectiveToolCount") : null)))
                    .effectiveFunctionCount(OpenApiProjectionSupport.firstNonNull(
                            OpenApiProjectionSupport.integerValue(taskState.get("effectiveFunctionCount")),
                            OpenApiProjectionSupport.integerValue(metadata != null
                                    ? metadata.get("effectiveFunctionCount") : null)))
                    .toolScopeSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("toolScopeSource")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("toolScopeSource") : null)))
                    .toolScopeKind(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("toolScopeKind")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("toolScopeKind") : null)))
                    .functionScopeSource(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("functionScopeSource")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("functionScopeSource") : null)))
                    .taskTokenFunctionScopeEmpty(OpenApiProjectionSupport.firstNonNull(
                            OpenApiProjectionSupport.booleanValue(taskState.get("taskTokenFunctionScopeEmpty")),
                            OpenApiProjectionSupport.booleanValue(metadata != null
                                    ? metadata.get("taskTokenFunctionScopeEmpty") : null)))
                    .runtimeDispatched(OpenApiProjectionSupport.firstNonNull(
                            OpenApiProjectionSupport.booleanValue(taskState.get("runtimeDispatched")),
                            OpenApiProjectionSupport.booleanValue(metadata != null
                                    ? metadata.get("runtimeDispatched") : null)))
                    .taskTokenStatus(OpenApiProjectionSupport.firstNonBlank(
                            OpenApiProjectionSupport.stringValue(taskState.get("taskTokenStatus")),
                            OpenApiProjectionSupport.stringValue(metadata != null
                                    ? metadata.get("taskTokenStatus") : null)));
            if (OpenApiProjectionSupport.hasText(taskEntity.getProviderTaskId())) {
                builder.workerTaskId(taskEntity.getProviderTaskId())
                        .providerTaskId(taskEntity.getProviderTaskId());
            }
            if (taskEntity.getLastAckedSeq() != null) {
                builder.lastAckedSeq(taskEntity.getLastAckedSeq());
            }
            if (OpenApiProjectionSupport.hasText(taskEntity.getErrorMessage())) {
                builder.errorMessage(OpenApiProjectionSupport.sanitizeDiagnosticText(
                        taskEntity.getErrorMessage()));
            }
            if (OpenApiProjectionSupport.hasText(taskEntity.getStatus())) {
                status = mapTaskStatus(taskEntity.getStatus());
                builder.status(status);
            }
        }

        String error = taskEntity != null
                ? taskEntity.getErrorMessage()
                : task.getStatus() != null ? task.getStatus().getDescription() : null;
        String failureSummary = "FAILED".equals(status)
                ? OpenApiProjectionSupport.sanitizeDiagnosticText(error)
                : null;
        if ("FAILED".equals(status) && !OpenApiProjectionSupport.hasText(failureSummary)) {
            failureSummary = "Task failed without persisted runtime messages.";
        }
        builder.failureSummary(failureSummary)
                .failureStage(taskEntity != null
                        ? inferFailureStage(taskEntity, failureSummary)
                        : inferFailureStageFromText(status, null, null, failureSummary));
        return builder.build();
    }

    OpenApiTaskDTO mapDurableTask(
            ObjectMapper objectMapper,
            SessionTaskEntity taskEntity,
            String agentId,
            String contextId) {
        Map<String, Object> taskState = parseTaskState(objectMapper, taskEntity.getTaskStateJson());
        String status = mapTaskStatus(taskEntity.getStatus());
        String failureSummary = "FAILED".equals(status) ? failureSummary(taskEntity, null) : null;
        String failureStage = inferFailureStage(taskEntity, failureSummary);
        return OpenApiTaskDTO.builder()
                .taskId(taskEntity.getTaskId())
                .agentId(agentId)
                .status(status)
                .contextId(contextId)
                .workerTaskId(taskEntity.getProviderTaskId())
                .providerTaskId(taskEntity.getProviderTaskId())
                .lastAckedSeq(taskEntity.getLastAckedSeq())
                .modelConfigId(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getModelConfigId(),
                        OpenApiProjectionSupport.stringValue(taskState.get("modelConfigId"))))
                .modelConfigSource(OpenApiProjectionSupport.stringValue(taskState.get("modelConfigSource")))
                .workerBackend(OpenApiProjectionSupport.firstNonBlank(
                        OpenApiProjectionSupport.stringValue(taskState.get("workerBackend")),
                        workerBackendFromProviderType(taskEntity.getProviderType())))
                .providerType(taskEntity.getProviderType())
                .taskSource(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getSource(),
                        OpenApiProjectionSupport.stringValue(taskState.get("taskSource"))))
                .workerSource(OpenApiProjectionSupport.stringValue(taskState.get("workerSource")))
                .backendSource(OpenApiProjectionSupport.stringValue(taskState.get("backendSource")))
                .effectiveToolCount(OpenApiProjectionSupport.integerValue(taskState.get("effectiveToolCount")))
                .effectiveFunctionCount(OpenApiProjectionSupport.integerValue(
                        taskState.get("effectiveFunctionCount")))
                .toolScopeSource(OpenApiProjectionSupport.stringValue(taskState.get("toolScopeSource")))
                .toolScopeKind(OpenApiProjectionSupport.stringValue(taskState.get("toolScopeKind")))
                .functionScopeSource(OpenApiProjectionSupport.stringValue(taskState.get("functionScopeSource")))
                .taskTokenFunctionScopeEmpty(OpenApiProjectionSupport.booleanValue(
                        taskState.get("taskTokenFunctionScopeEmpty")))
                .runtimeDispatched(OpenApiProjectionSupport.booleanValue(taskState.get("runtimeDispatched")))
                .taskTokenStatus(OpenApiProjectionSupport.stringValue(taskState.get("taskTokenStatus")))
                .failureStage(failureStage)
                .failureSummary(failureSummary)
                .errorMessage(OpenApiProjectionSupport.sanitizeDiagnosticText(taskEntity.getErrorMessage()))
                .result(taskEntity.getResultText())
                .durationMs(taskEntity.getDurationMs())
                .costUsd(taskEntity.getCostUsd())
                .createdAt(taskEntity.getCreatedAt())
                .updatedAt(taskEntity.getUpdatedAt())
                .build();
    }

    OpenApiTaskDTO mapActiveTask(DispatchTaskDTO task, String agentId) {
        return OpenApiTaskDTO.builder()
                .taskId(task.getTaskId())
                .agentId(agentId)
                .status(mapTaskStatus(task.getStatus()))
                .contextId(task.getContextId())
                .workerTaskId(task.getWorkerTaskId())
                .providerTaskId(task.getWorkerTaskId())
                .lastAckedSeq(task.getLastAckedSeq())
                .modelConfigId(task.getModelConfigId())
                .providerType(task.getProviderType())
                .taskSource(task.getSource())
                .workerBackend(workerBackendFromProviderType(task.getProviderType()))
                .createdAt(task.getCreatedAt())
                .build();
    }

    TaskMessageProjection projectTaskMessages(
            ObjectMapper objectMapper,
            SessionTaskEntity taskEntity,
            List<OpenSessionMessageDTO> messages) {
        TaskStatusProjection status = projectStatus(taskEntity.getStatus());
        Map<String, Object> taskState = parseTaskState(objectMapper, taskEntity.getTaskStateJson());
        String failureSummary = failureSummary(taskEntity, messages);
        return new TaskMessageProjection(
                status,
                taskState,
                failureSummary,
                inferFailureStage(taskEntity, failureSummary));
    }

    OpenTaskDiagnosticsDTO mapDiagnostics(
            ObjectMapper objectMapper,
            SessionTaskEntity taskEntity,
            String agentId,
            String contextId,
            LocalDateTime lastMessageAt,
            long messagesCount) {
        Map<String, Object> taskState = parseTaskState(objectMapper, taskEntity.getTaskStateJson());
        String status = mapTaskStatus(taskEntity.getStatus());
        String terminalStatus = terminalStatusFromTaskStatus(status);
        String failureSummary = "FAILED".equals(status) ? failureSummary(taskEntity, null) : null;
        String workerBackend = OpenApiProjectionSupport.firstNonBlank(
                OpenApiProjectionSupport.stringValue(taskState.get("workerBackend")),
                workerBackendFromProviderType(taskEntity.getProviderType()));
        LocalDateTime lastObservedAt = OpenApiProjectionSupport.latestTime(
                taskEntity.getLastAliveAt(),
                lastMessageAt,
                taskEntity.getUpdatedAt(),
                taskEntity.getCreatedAt());

        return OpenTaskDiagnosticsDTO.builder()
                .taskId(taskEntity.getTaskId())
                .agentId(agentId)
                .contextId(contextId)
                .status(status)
                .terminal(terminalStatus != null)
                .terminalStatus(terminalStatus)
                .submittedAt(OpenApiProjectionSupport.firstNonNull(
                        taskEntity.getCreatedAt(),
                        OpenApiProjectionSupport.localDateTimeValue(
                                taskState, "submittedAt", "submitted_at")))
                .workerStartedAt(OpenApiProjectionSupport.localDateTimeValue(
                        taskState,
                        "workerStartedAt", "worker_started_at", "workerAcceptedAt",
                        "worker_accepted_at", "startedAt", "started_at"))
                .lastObservedAt(lastObservedAt)
                .messagesCount(messagesCount)
                .workerTaskId(taskEntity.getProviderTaskId())
                .providerTaskId(taskEntity.getProviderTaskId())
                .lastAckedSeq(taskEntity.getLastAckedSeq() != null
                        ? taskEntity.getLastAckedSeq().longValue() : null)
                .modelConfigId(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getModelConfigId(),
                        OpenApiProjectionSupport.stringValue(taskState.get("modelConfigId"))))
                .modelConfigSource(OpenApiProjectionSupport.stringValue(taskState.get("modelConfigSource")))
                .workerBackend(workerBackend)
                .providerType(taskEntity.getProviderType())
                .taskSource(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getSource(),
                        OpenApiProjectionSupport.stringValue(taskState.get("taskSource"))))
                .workerSource(OpenApiProjectionSupport.stringValue(taskState.get("workerSource")))
                .backendSource(OpenApiProjectionSupport.stringValue(taskState.get("backendSource")))
                .safeWorkerRef(OpenApiProjectionSupport.sanitizeDiagnosticText(taskEntity.getWorkerId()))
                .failureStage(inferFailureStage(taskEntity, failureSummary))
                .failureSummary(failureSummary)
                .cancelCapability(buildCancelCapability(status, workerBackend))
                .correlation(buildTaskCorrelation(taskState))
                .createdAt(taskEntity.getCreatedAt())
                .updatedAt(taskEntity.getUpdatedAt())
                .build();
    }

    OpenTaskEvidenceDTO mapEvidence(
            ObjectMapper objectMapper,
            SessionTaskEntity taskEntity,
            String agentId,
            String contextId,
            List<SessionMessageEntity> messages) {
        Map<String, Object> taskState = parseTaskState(objectMapper, taskEntity.getTaskStateJson());
        String status = mapTaskStatus(taskEntity.getStatus());
        String terminalStatus = terminalStatusFromTaskStatus(status);
        List<OpenTaskReportRefDTO> reportRefs = new ArrayList<>();
        List<OpenTaskArtifactRefDTO> artifactRefs = new ArrayList<>();

        OpenApiProjectionSupport.collectReportRefs(reportRefs, OpenApiProjectionSupport.firstPresent(
                taskState,
                "reportRef", "report_ref", "frameReportRef", "frame_report_ref",
                "executionReportRef", "execution_report_ref", "reportRefs", "report_refs"));
        OpenApiProjectionSupport.collectArtifactRefs(artifactRefs, OpenApiProjectionSupport.firstPresent(
                taskState, "artifactRefs", "artifact_refs", "artifacts"));

        if (messages != null) {
            for (SessionMessageEntity message : messages) {
                Map<String, Object> metadata = OpenApiProjectionSupport.parseMessageMetadata(
                        objectMapper, message);
                OpenApiProjectionSupport.collectReportRefs(
                        reportRefs,
                        OpenApiProjectionSupport.firstPresent(
                                metadata,
                                "reportRef", "report_ref", "frameReportRef", "frame_report_ref",
                                "executionReportRef", "execution_report_ref", "reportRefs", "report_refs"));
                OpenApiProjectionSupport.collectArtifactRefs(
                        artifactRefs,
                        OpenApiProjectionSupport.firstPresent(
                                metadata, "artifactRefs", "artifact_refs", "artifacts"));
            }
        }

        return OpenTaskEvidenceDTO.builder()
                .taskId(taskEntity.getTaskId())
                .agentId(agentId)
                .contextId(contextId)
                .status(status)
                .terminal(terminalStatus != null)
                .terminalStatus(terminalStatus)
                .finalAnswer(buildFinalAnswer(objectMapper, taskEntity, messages))
                .structuredOutput(buildStructuredOutput(objectMapper, taskState, messages))
                .reportRefs(reportRefs)
                .artifactRefs(artifactRefs)
                .build();
    }

    OpenTaskCancelCapabilityDTO buildCancelCapability(String status, String workerBackend) {
        List<String> limitations = new ArrayList<>();
        String terminalStatus = terminalStatusFromTaskStatus(status);
        if (terminalStatus != null) {
            limitations.add("terminal_task");
        }
        limitations.add("runtime_client_app_cancel_not_exposed");
        if (!OpenApiProjectionSupport.hasText(workerBackend)) {
            limitations.add("backend_cancel_capability_not_declared");
        }
        return OpenTaskCancelCapabilityDTO.builder()
                .cancelSupported(false)
                .cancelMode(terminalStatus != null ? "none" : "admin_only")
                .cleanupSupported(false)
                .backendLimitations(limitations)
                .build();
    }

    OpenTaskCorrelationDTO buildTaskCorrelation(Map<String, Object> taskState) {
        if (taskState == null || taskState.isEmpty()) {
            return null;
        }
        String originalTaskId = OpenApiProjectionSupport.stringValue(OpenApiProjectionSupport.firstPresent(
                taskState, "originalTaskId", "original_task_id", "sourceTaskId", "source_task_id"));
        String recoveryCorrelationKey = OpenApiProjectionSupport.stringValue(
                OpenApiProjectionSupport.firstPresent(
                        taskState,
                        "recoveryCorrelationKey", "recovery_correlation_key",
                        "correlationKey", "correlation_key"));
        Integer attemptNumber = OpenApiProjectionSupport.integerValue(OpenApiProjectionSupport.firstPresent(
                taskState, "attemptNumber", "attempt_number", "attempt"));
        String idempotencyKey = OpenApiProjectionSupport.stringValue(OpenApiProjectionSupport.firstPresent(
                taskState, "idempotencyKey", "idempotency_key"));
        if (!OpenApiProjectionSupport.hasText(originalTaskId)
                && !OpenApiProjectionSupport.hasText(recoveryCorrelationKey)
                && !OpenApiProjectionSupport.hasText(idempotencyKey)
                && attemptNumber == null) {
            return null;
        }
        return OpenTaskCorrelationDTO.builder()
                .originalTaskId(OpenApiProjectionSupport.sanitizeDiagnosticText(originalTaskId))
                .recoveryCorrelationKey(OpenApiProjectionSupport.sanitizeDiagnosticText(
                        recoveryCorrelationKey))
                .attemptNumber(attemptNumber)
                .idempotencyKey(OpenApiProjectionSupport.sanitizeDiagnosticText(idempotencyKey))
                .build();
    }

    OpenTaskFinalAnswerDTO buildFinalAnswer(
            ObjectMapper objectMapper,
            SessionTaskEntity taskEntity,
            List<SessionMessageEntity> messages) {
        if (OpenApiProjectionSupport.hasText(taskEntity.getResultText())) {
            return OpenTaskFinalAnswerDTO.builder()
                    .available(true)
                    .summary(OpenApiProjectionSupport.sanitizeDiagnosticText(taskEntity.getResultText()))
                    .source("task_result")
                    .createdAt(OpenApiProjectionSupport.firstNonNull(
                            taskEntity.getUpdatedAt(), taskEntity.getCreatedAt()))
                    .build();
        }
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                SessionMessageEntity message = messages.get(index);
                if (!BusinessAgentSessionMessageVisibility.isVisibleByDefault(message)
                        || !OpenApiProjectionSupport.hasText(message.getContent())) {
                    continue;
                }
                Map<String, Object> metadata = OpenApiProjectionSupport.parseMessageMetadata(
                        objectMapper, message);
                String type = OpenApiProjectionSupport.inferMessageType(message.getRole(), metadata);
                if ("RESULT".equals(type) || "TEXT".equals(type)) {
                    return OpenTaskFinalAnswerDTO.builder()
                            .available(true)
                            .summary(OpenApiProjectionSupport.sanitizeDiagnosticText(message.getContent()))
                            .messageId(message.getId())
                            .source("message")
                            .createdAt(message.getCreatedAt())
                            .build();
                }
            }
        }
        return OpenTaskFinalAnswerDTO.builder().available(false).build();
    }

    OpenTaskStructuredOutputDTO buildStructuredOutput(
            ObjectMapper objectMapper,
            Map<String, Object> taskState,
            List<SessionMessageEntity> messages) {
        Object value = OpenApiProjectionSupport.firstPresent(
                taskState, "structuredOutput", "structured_output", "outputJson", "output_json");
        if (value != null) {
            return OpenTaskStructuredOutputDTO.builder()
                    .available(true)
                    .value(OpenApiProjectionSupport.sanitizeEvidenceValue(value))
                    .source("task_state")
                    .build();
        }
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                SessionMessageEntity message = messages.get(index);
                Map<String, Object> metadata = OpenApiProjectionSupport.parseMessageMetadata(
                        objectMapper, message);
                value = OpenApiProjectionSupport.firstPresent(
                        metadata, "structuredOutput", "structured_output", "outputJson", "output_json");
                if (value != null) {
                    return OpenTaskStructuredOutputDTO.builder()
                            .available(true)
                            .value(OpenApiProjectionSupport.sanitizeEvidenceValue(value))
                            .source("message_metadata")
                            .build();
                }
                if (BusinessAgentSessionMessageVisibility.isVisibleByDefault(message)) {
                    value = extractStructuredOutputFromMessageContent(objectMapper, message.getContent());
                    if (value != null) {
                        return OpenTaskStructuredOutputDTO.builder()
                                .available(true)
                                .value(OpenApiProjectionSupport.sanitizeEvidenceValue(value))
                                .source("message_content")
                                .build();
                    }
                }
            }
        }
        return OpenTaskStructuredOutputDTO.builder().available(false).build();
    }

    Object extractStructuredOutputFromMessageContent(ObjectMapper objectMapper, String content) {
        if (!OpenApiProjectionSupport.hasText(content)) {
            return null;
        }
        String text = content.trim();
        if (text.length() > MAX_STRUCTURED_OUTPUT_CONTENT_LENGTH
                || !text.startsWith("{")
                || !text.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> contentMap = objectMapper.readValue(
                    text, new TypeReference<Map<String, Object>>() {});
            Object direct = OpenApiProjectionSupport.firstPresent(
                    contentMap, "structuredOutput", "structured_output", "outputJson", "output_json");
            if (direct != null) {
                return direct;
            }
            Map<String, Object> flattened = extractFlattenedStructuredOutput(contentMap);
            if (!flattened.isEmpty()) {
                return flattened;
            }
            if (isOpenArtifactType(contentMap.get("type"))) {
                return contentMap;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    Map<String, Object> extractFlattenedStructuredOutput(Map<String, Object> contentMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (contentMap == null || contentMap.isEmpty()) {
            return result;
        }
        contentMap.forEach((key, value) -> {
            String path = null;
            if (key.startsWith("structured_output.")) {
                path = key.substring("structured_output.".length());
            } else if (key.startsWith("structuredOutput.")) {
                path = key.substring("structuredOutput.".length());
            }
            if (OpenApiProjectionSupport.hasText(path)) {
                putDottedPath(result, path, value);
            }
        });
        return result;
    }

    private void putDottedPath(Map<String, Object> target, String dottedPath, Object value) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> current = target;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (!OpenApiProjectionSupport.hasText(part)) {
                return;
            }
            if (index == parts.length - 1) {
                current.put(part, value);
                return;
            }
            Object child = current.get(part);
            Map<String, Object> childMap = child instanceof Map<?, ?> rawMap
                    ? OpenApiProjectionSupport.toStringObjectMap(rawMap)
                    : new LinkedHashMap<>();
            current.put(part, childMap);
            current = childMap;
        }
    }

    private boolean isOpenArtifactType(Object value) {
        return value instanceof String text && "OPEN_ARTIFACT".equalsIgnoreCase(text.trim());
    }

    String inferFailureStageFromText(
            String status,
            String providerType,
            String workerBackend,
            String failureSummary) {
        if (!"FAILED".equals(status)) {
            return null;
        }
        String stage = inferFailureStage(failureSummary);
        if (stage != null) {
            return stage;
        }
        String providerWorkerBackend = ProviderRouteRegistry.workerBackendForRouteTokenOrNull(providerType);
        String normalizedWorkerBackend = ProviderRouteRegistry.canonicalWorkerBackendOrNull(workerBackend);
        if (BACKEND_OPENAI_CODEX.equals(providerWorkerBackend)
                || BACKEND_OPENAI_CODEX.equals(normalizedWorkerBackend)
                || BACKEND_OPENAI_CODEX_APP_SERVER.equals(providerWorkerBackend)
                || BACKEND_OPENAI_CODEX_APP_SERVER.equals(normalizedWorkerBackend)) {
            return "RUNTIME";
        }
        return "DISPATCH";
    }

    String workerBackendFromProviderType(String providerType) {
        if (!OpenApiProjectionSupport.hasText(providerType)) {
            return null;
        }
        String workerBackend = ProviderRouteRegistry.workerBackendForRouteTokenOrNull(providerType);
        return workerBackend != null
                ? workerBackend
                : providerType.trim().toUpperCase(Locale.ROOT);
    }

    Map<String, Object> parseTaskState(ObjectMapper objectMapper, String json) {
        return OpenApiProjectionSupport.parseMapOrEmpty(objectMapper, json);
    }

    String sanitizeDiagnosticText(String text) {
        return OpenApiProjectionSupport.sanitizeDiagnosticText(text);
    }

    private String inferFailureStage(String errorMessage) {
        if (!OpenApiProjectionSupport.hasText(errorMessage)) {
            return null;
        }
        String text = errorMessage.toLowerCase(Locale.ROOT);
        if (text.contains("api key") || text.contains("apikey") || text.contains("authorization")
                || text.contains("unauthorized") || text.contains("401") || text.contains("403")
                || text.contains("429") || text.contains("quota") || text.contains("rate limit")
                || text.contains("insufficient_quota") || text.contains("model_not_found")
                || text.contains("provider api") || text.contains("openai")
                || text.contains("anthropic") || text.contains("gemini api")) {
            return "PROVIDER_API";
        }
        if (text.contains("codex not configured")
                || text.contains("failed to connect to codex worker")
                || text.contains("connection refused")
                || text.contains("timeout")
                || text.contains("timed out")
                || text.contains("econnrefused")
                || text.contains("sse")
                || text.contains("stream")
                || text.contains("transport")
                || text.contains("worker")) {
            return "WORKER_TRANSPORT";
        }
        return null;
    }

    private String inferFailureStage(SessionTaskEntity taskEntity, String failureSummary) {
        String stage = inferFailureStage(failureSummary);
        if (stage != null) {
            return stage;
        }
        if (taskEntity == null || !"FAILED".equals(mapTaskStatus(taskEntity.getStatus()))) {
            return null;
        }
        if (OpenApiProjectionSupport.hasText(taskEntity.getProviderTaskId())
                || (taskEntity.getLastAckedSeq() != null && taskEntity.getLastAckedSeq() > 0)) {
            return "RUNTIME";
        }
        return "DISPATCH";
    }

    private String failureSummary(
            SessionTaskEntity taskEntity,
            List<OpenSessionMessageDTO> messages) {
        if (messages != null) {
            for (OpenSessionMessageDTO message : messages) {
                if (message != null
                        && "ERROR".equalsIgnoreCase(message.getType())
                        && OpenApiProjectionSupport.hasText(message.getContent())) {
                    return OpenApiProjectionSupport.sanitizeDiagnosticText(message.getContent());
                }
            }
        }
        if (taskEntity != null && OpenApiProjectionSupport.hasText(taskEntity.getErrorMessage())) {
            return OpenApiProjectionSupport.sanitizeDiagnosticText(taskEntity.getErrorMessage());
        }
        if (taskEntity != null && "FAILED".equals(mapTaskStatus(taskEntity.getStatus()))) {
            return "Task failed without persisted runtime messages.";
        }
        return null;
    }

    record TaskStatusProjection(
            String responseStatus,
            String messageStatus,
            String terminalStatus) {
    }

    record TaskMessageProjection(
            TaskStatusProjection status,
            Map<String, Object> taskState,
            String failureSummary,
            String failureStage) {
    }
}

/** Shared pure Open API projection primitives; malformed Task and Session JSON use distinct APIs. */
final class OpenApiProjectionSupport {

    private OpenApiProjectionSupport() {
    }

    static Map<String, Object> parseMapOrEmpty(ObjectMapper objectMapper, String json) {
        Map<String, Object> value = parseNullableMap(objectMapper, json);
        return value != null ? value : Map.of();
    }

    static Map<String, Object> parseNullableMap(ObjectMapper objectMapper, String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    static Map<String, Object> parseMessageMetadata(
            ObjectMapper objectMapper,
            SessionMessageEntity message) {
        if (message == null) {
            return Map.of();
        }
        return parseMapOrEmpty(objectMapper, message.getMetadata());
    }

    static List<OpenTaskReportRefDTO> extractReportRefs(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        List<OpenTaskReportRefDTO> refs = new ArrayList<>();
        collectReportRefs(refs, firstPresent(
                metadata,
                "reportRef", "report_ref", "frameReportRef", "frame_report_ref",
                "executionReportRef", "execution_report_ref", "reportRefs", "report_refs"));
        return refs.isEmpty() ? null : refs;
    }

    static void collectReportRefs(List<OpenTaskReportRefDTO> refs, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collectReportRefs(refs, item);
            }
            return;
        }
        OpenTaskReportRefDTO dto = null;
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = toStringObjectMap(rawMap);
            String ref = stringValue(firstPresent(
                    map, "ref", "reportRef", "report_ref", "executionReportRef", "execution_report_ref"));
            dto = OpenTaskReportRefDTO.builder()
                    .type(firstNonBlank(stringValue(map.get("type")), inferReportRefType(ref)))
                    .ref(sanitizeDiagnosticText(ref))
                    .frameId(sanitizeDiagnosticText(firstNonBlank(
                            stringValue(map.get("frameId")), inferFrameId(ref))))
                    .summary(sanitizeDiagnosticText(stringValue(map.get("summary"))))
                    .build();
        } else if (raw instanceof String text && hasText(text)) {
            dto = OpenTaskReportRefDTO.builder()
                    .type(inferReportRefType(text))
                    .ref(sanitizeDiagnosticText(text))
                    .frameId(sanitizeDiagnosticText(inferFrameId(text)))
                    .build();
        }
        if (dto != null && hasText(dto.getRef())) {
            String ref = dto.getRef();
            if (refs.stream().noneMatch(existing -> ref.equals(existing.getRef()))) {
                refs.add(dto);
            }
        }
    }

    static List<OpenTaskArtifactRefDTO> extractArtifactRefs(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        List<OpenTaskArtifactRefDTO> refs = new ArrayList<>();
        collectArtifactRefs(refs, firstPresent(metadata, "artifactRefs", "artifact_refs", "artifacts"));
        return refs.isEmpty() ? null : refs;
    }

    static void collectArtifactRefs(List<OpenTaskArtifactRefDTO> refs, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collectArtifactRefs(refs, item);
            }
            return;
        }
        OpenTaskArtifactRefDTO dto = null;
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = toStringObjectMap(rawMap);
            dto = OpenTaskArtifactRefDTO.builder()
                    .path(safeArtifactRef(firstNonBlank(
                            stringValue(map.get("path")), stringValue(map.get("file")))))
                    .ref(safeArtifactRef(firstNonBlank(
                            stringValue(map.get("ref")), stringValue(map.get("id")))))
                    .summary(sanitizeDiagnosticText(stringValue(map.get("summary"))))
                    .hash(sanitizeDiagnosticText(stringValue(map.get("hash"))))
                    .mtime(sanitizeDiagnosticText(firstNonBlank(
                            stringValue(map.get("mtime")), stringValue(map.get("modifiedAt")))))
                    .build();
        } else if (raw instanceof String text && hasText(text)) {
            dto = OpenTaskArtifactRefDTO.builder().path(safeArtifactRef(text)).build();
        }
        if (dto != null && (hasText(dto.getPath()) || hasText(dto.getRef()))) {
            String key = firstNonBlank(dto.getRef(), dto.getPath());
            if (refs.stream().noneMatch(existing ->
                    key.equals(firstNonBlank(existing.getRef(), existing.getPath())))) {
                refs.add(dto);
            }
        }
    }

    static String inferMessageType(String role, Map<String, Object> metadata) {
        if ("USER".equalsIgnoreCase(role)) {
            return "USER";
        }
        if ("SYSTEM".equalsIgnoreCase(role)) {
            return "STATE";
        }
        if (metadata != null) {
            Object rawType = metadata.get("type");
            if (rawType instanceof String type) {
                return switch (type) {
                    case "TEXT_COMPLETE" -> "TEXT";
                    case "TOOL_CALL_START" -> "TOOL_CALL";
                    case "TOOL_CALL_RESULT", "TOOL_CALL_ERROR" -> "TOOL_RESULT";
                    case "TASK_COMPLETED" -> "RESULT";
                    case "STATE_SYNC" -> "STATE";
                    case "ERROR" -> "ERROR";
                    default -> "TEXT";
                };
            }
        }
        return "TEXT";
    }

    static Object sanitizeEvidenceValue(Object value) {
        if (value instanceof String text) {
            return sanitizeDiagnosticText(text);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((key, childValue) -> {
                if (key instanceof String keyText) {
                    sanitized.put(keyText, sanitizeEvidenceValue(childValue));
                }
            });
            return sanitized;
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(OpenApiProjectionSupport::sanitizeEvidenceValue).toList();
        }
        return value;
    }

    static String sanitizeDiagnosticText(String text) {
        if (!hasText(text)) {
            return null;
        }
        String sanitized = text.replace('\n', ' ').replace('\r', ' ').trim()
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(access[_-]?token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(client[_-]?secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-[REDACTED]");
        return truncate(sanitized, 500);
    }

    static String safeArtifactRef(String value) {
        String sanitized = sanitizeDiagnosticText(value);
        if (!hasText(sanitized)) {
            return null;
        }
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex > 0) {
            sanitized = sanitized.substring(0, queryIndex);
        }
        return truncate(sanitized, 300);
    }

    static String inferReportRefType(String ref) {
        if (!hasText(ref)) {
            return null;
        }
        return ref.startsWith("frame-report://") ? "frame_report" : "report";
    }

    static String inferFrameId(String ref) {
        if (!hasText(ref) || !ref.startsWith("frame-report://")) {
            return null;
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : null;
    }

    static Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key instanceof String text) {
                map.put(text, value);
            }
        });
        return map;
    }

    static Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    @SafeVarargs
    static <T> T firstNonNull(T... values) {
        if (values != null) {
            for (T value : values) {
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return hasText(text) ? text : null;
    }

    static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && hasText(text)
                && ("true".equalsIgnoreCase(text.trim())
                || "false".equalsIgnoreCase(text.trim()))) {
            return Boolean.parseBoolean(text.trim());
        }
        return null;
    }

    static LocalDateTime latestTime(LocalDateTime... values) {
        LocalDateTime latest = null;
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    static LocalDateTime localDateTimeValue(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (!(value instanceof String text) || !hasText(text)) {
            return null;
        }
        String normalized = text.trim();
        try {
            return LocalDateTime.parse(normalized);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}

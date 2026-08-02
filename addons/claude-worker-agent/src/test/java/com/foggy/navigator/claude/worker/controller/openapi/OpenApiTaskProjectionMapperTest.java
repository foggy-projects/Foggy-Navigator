package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.OpenApiTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskDiagnosticsDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskEvidenceDTO;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiTaskProjectionMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenApiTaskProjectionMapper mapper = new OpenApiTaskProjectionMapper();

    @Test
    void mapsStatusWithoutPromotingMissingOrUnknownFacts() {
        OpenApiTaskProjectionMapper.TaskStatusProjection missing = mapper.projectStatus(null);
        assertEquals("UNKNOWN", missing.responseStatus());
        assertNull(missing.messageStatus());
        assertNull(missing.terminalStatus());

        OpenApiTaskProjectionMapper.TaskStatusProjection blank = mapper.projectStatus(" ");
        assertEquals(" ", blank.responseStatus());
        assertNull(blank.messageStatus());
        assertNull(blank.terminalStatus());

        OpenApiTaskProjectionMapper.TaskStatusProjection completed = mapper.projectStatus("COMPLETED");
        assertEquals("COMPLETED", completed.responseStatus());
        assertEquals("COMPLETED", completed.messageStatus());
        assertEquals("COMPLETED", completed.terminalStatus());

        assertEquals("SUBMITTED", mapper.mapTaskStatus("PENDING"));
        assertEquals("CANCELLED", mapper.mapTaskStatus("ABORTED"));
        assertEquals("FUTURE_STATUS", mapper.mapTaskStatus("FUTURE_STATUS"));
        assertEquals("RUNNING", mapper.mapA2aState(A2aTaskState.WORKING));
        assertEquals("UNKNOWN", mapper.mapA2aState(null));
    }

    @Test
    void durableProjectionOverridesStaleA2aFactsWithExistingFieldPrecedence() {
        A2aTask providerTask = A2aTask.builder()
                .id("task-1")
                .contextId("ctx-provider")
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.WORKING)
                        .description("provider still working")
                        .build())
                .artifacts(List.of(A2aArtifact.builder()
                        .parts(List.of(A2aPart.text("provider artifact result")))
                        .build()))
                .metadata(Map.ofEntries(
                        Map.entry("workerTaskId", "provider-task"),
                        Map.entry("lastAckedSeq", 3),
                        Map.entry("durationMs", 41),
                        Map.entry("costUsd", 1.5d),
                        Map.entry("modelConfigId", "metadata-model"),
                        Map.entry("modelConfigSource", "metadata-source"),
                        Map.entry("workerBackend", "metadata-backend"),
                        Map.entry("providerType", "metadata-provider"),
                        Map.entry("taskSource", "metadata-task-source"),
                        Map.entry("workerSource", "metadata-worker-source"),
                        Map.entry("backendSource", "metadata-backend-source"),
                        Map.entry("effectiveToolCount", 2),
                        Map.entry("effectiveFunctionCount", 3),
                        Map.entry("runtimeDispatched", false)))
                .build();
        SessionTaskEntity durable = durableTask("ABORTED");
        durable.setProviderTaskId("durable-provider-task");
        durable.setLastAckedSeq(9);
        durable.setProviderType("codex-app-server-worker");
        durable.setModelConfigId("durable-model");
        durable.setSource("DURABLE_SOURCE");
        durable.setResultText("durable result does not replace provider artifact");
        durable.setErrorMessage("token=raw-secret");
        durable.setTaskStateJson("""
                {
                  "modelConfigSource":"durable-model-source",
                  "workerBackend":"durable-backend",
                  "workerSource":"durable-worker-source",
                  "backendSource":"durable-backend-source",
                  "effectiveToolCount":7,
                  "effectiveFunctionCount":8,
                  "runtimeDispatched":true
                }
                """);

        OpenApiTaskDTO result = mapper.mapA2aTask(
                objectMapper, providerTask, "agent-1", durable);

        assertEquals("CANCELLED", result.getStatus());
        assertEquals("provider artifact result", result.getResult());
        assertEquals("ctx-provider", result.getContextId());
        assertEquals("durable-provider-task", result.getWorkerTaskId());
        assertEquals("durable-provider-task", result.getProviderTaskId());
        assertEquals(9, result.getLastAckedSeq());
        assertEquals("codex-app-server-worker", result.getProviderType());
        assertEquals("durable-model", result.getModelConfigId());
        assertEquals("durable-model-source", result.getModelConfigSource());
        assertEquals("durable-backend", result.getWorkerBackend());
        assertEquals("DURABLE_SOURCE", result.getTaskSource());
        assertEquals("durable-worker-source", result.getWorkerSource());
        assertEquals("durable-backend-source", result.getBackendSource());
        assertEquals(7, result.getEffectiveToolCount());
        assertEquals(8, result.getEffectiveFunctionCount());
        assertTrue(result.getRuntimeDispatched());
        assertFalse(result.getErrorMessage().contains("raw-secret"));
        assertNull(result.getFailureSummary());
    }

    @Test
    void mapsDurableAndActiveTasksWithoutSanitizingDurableResultText() {
        SessionTaskEntity durable = durableTask("FAILED");
        durable.setTaskStateJson("{malformed");
        durable.setProviderType("codex-biz-worker");
        durable.setResultText("raw result token=must-remain-on-task-dto");
        durable.setErrorMessage("api_key=sk-abcdefghijklmnop");

        OpenApiTaskDTO durableResult = mapper.mapDurableTask(
                objectMapper, durable, "agent-1", "ctx-1");

        assertEquals("FAILED", durableResult.getStatus());
        assertEquals("raw result token=must-remain-on-task-dto", durableResult.getResult());
        assertFalse(durableResult.getErrorMessage().contains("sk-abcdefghijklmnop"));
        assertFalse(durableResult.getFailureSummary().contains("sk-abcdefghijklmnop"));
        assertEquals("DISPATCH", durableResult.getFailureStage());
        assertEquals("OPENAI_CODEX", durableResult.getWorkerBackend());
        assertTrue(mapper.parseTaskState(objectMapper, durable.getTaskStateJson()).isEmpty());

        DispatchTaskDTO active = DispatchTaskDTO.builder()
                .taskId("active-1")
                .status("ABORTED")
                .contextId("ctx-active")
                .workerTaskId("worker-active")
                .lastAckedSeq(4)
                .modelConfigId("model-active")
                .providerType("codex-biz-worker")
                .source("ACTIVE_SOURCE")
                .createdAt(LocalDateTime.of(2026, 8, 3, 10, 0))
                .build();
        OpenApiTaskDTO activeResult = mapper.mapActiveTask(active, "agent-1");
        assertEquals("CANCELLED", activeResult.getStatus());
        assertEquals("OPENAI_CODEX", activeResult.getWorkerBackend());
        assertEquals("worker-active", activeResult.getProviderTaskId());

        SessionTaskEntity missingStatus = durableTask(null);
        OpenApiTaskDTO missingResult = mapper.mapDurableTask(
                objectMapper, missingStatus, "agent-1", "ctx-1");
        assertEquals("UNKNOWN", missingResult.getStatus());
    }

    @Test
    void mapsDiagnosticsOnlyFromEntityAndCallerPreloadedFacts() {
        SessionTaskEntity task = durableTask("RUNNING");
        task.setProviderTaskId("provider-task-1");
        task.setLastAckedSeq(7);
        task.setWorkerId("worker-1 token=raw-secret");
        task.setModelConfigId("model-1");
        task.setLastAliveAt(LocalDateTime.of(2026, 8, 3, 10, 2, 30));
        task.setTaskStateJson("""
                {
                  "submittedAt":"2026-08-03T09:59:00",
                  "workerStartedAt":"2026-08-03T10:01:00",
                  "workerBackend":"claude-worker",
                  "modelConfigSource":"agent_default",
                  "originalTaskId":"task-original",
                  "recoveryCorrelationKey":"corr-1",
                  "attemptNumber":2,
                  "idempotencyKey":"Bearer abcdefgh123456"
                }
                """);
        LocalDateTime latestMessageAt = LocalDateTime.of(2026, 8, 3, 10, 3);

        OpenTaskDiagnosticsDTO result = mapper.mapDiagnostics(
                objectMapper, task, "agent-1", "ctx-1", latestMessageAt, 5L);

        assertEquals("RUNNING", result.getStatus());
        assertFalse(result.getTerminal());
        assertEquals(task.getCreatedAt(), result.getSubmittedAt());
        assertEquals(LocalDateTime.of(2026, 8, 3, 10, 1), result.getWorkerStartedAt());
        assertEquals(latestMessageAt, result.getLastObservedAt());
        assertEquals(5L, result.getMessagesCount());
        assertEquals(7L, result.getLastAckedSeq());
        assertFalse(result.getSafeWorkerRef().contains("raw-secret"));
        assertEquals("admin_only", result.getCancelCapability().getCancelMode());
        assertEquals("task-original", result.getCorrelation().getOriginalTaskId());
        assertFalse(result.getCorrelation().getIdempotencyKey().contains("abcdefgh123456"));
    }

    @Test
    void mapsEvidenceWithTaskStateFirstReferenceOrderAndSanitizedSummaries() {
        SessionTaskEntity task = durableTask("COMPLETED");
        task.setResultText("done api_key=sk-secret-token");
        task.setTaskStateJson("""
                {
                  "structuredOutput":{"status":"ok","token":"sk-secret-token"},
                  "reportRefs":["frame-report://task/frame-1"],
                  "artifactRefs":[{"ref":"artifact://task?token=secret","summary":"task artifact"}]
                }
                """);
        SessionMessageEntity message = message(
                "message-1",
                "ignored because task result wins",
                """
                        {
                          "type":"TEXT",
                          "reportRefs":["frame-report://task/frame-1","report://message"],
                          "artifactRefs":[
                            {"ref":"artifact://task?token=other"},
                            {"path":"/workspace/message.txt?signature=secret"}
                          ]
                        }
                        """);

        OpenTaskEvidenceDTO result = mapper.mapEvidence(
                objectMapper, task, "agent-1", "ctx-1", List.of(message));

        assertEquals("COMPLETED", result.getStatus());
        assertTrue(result.getTerminal());
        assertTrue(result.getFinalAnswer().getAvailable());
        assertEquals("task_result", result.getFinalAnswer().getSource());
        assertFalse(result.getFinalAnswer().getSummary().contains("sk-secret-token"));
        assertTrue(result.getStructuredOutput().getAvailable());
        assertEquals("task_state", result.getStructuredOutput().getSource());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.getStructuredOutput().getValue();
        assertFalse(String.valueOf(structured.get("token")).contains("sk-secret-token"));
        assertEquals(List.of("frame-report://task/frame-1", "report://message"),
                result.getReportRefs().stream().map(ref -> ref.getRef()).toList());
        assertEquals(List.of("artifact://task", "/workspace/message.txt"),
                result.getArtifactRefs().stream()
                        .map(ref -> ref.getRef() != null ? ref.getRef() : ref.getPath())
                        .toList());
    }

    @Test
    void liftsCaseInsensitiveOpenArtifactButRejectsMalformedAndOversizedJsonContent() {
        SessionTaskEntity task = durableTask("COMPLETED");
        task.setTaskStateJson("{malformed");
        SessionMessageEntity openArtifact = message(
                "message-open",
                """
                        {
                          "type":"open_artifact",
                          "artifact":{"uri":"https://example.test/report?token=secret"}
                        }
                        """,
                "{\"type\":\"task_completed\"}");

        OpenTaskEvidenceDTO lifted = mapper.mapEvidence(
                objectMapper, task, "agent-1", "ctx-1", List.of(openArtifact));
        assertTrue(lifted.getStructuredOutput().getAvailable());
        assertEquals("message_content", lifted.getStructuredOutput().getSource());
        @SuppressWarnings("unchecked")
        Map<String, Object> liftedValue = (Map<String, Object>) lifted.getStructuredOutput().getValue();
        assertEquals("open_artifact", liftedValue.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) liftedValue.get("artifact");
        assertFalse(String.valueOf(artifact.get("uri")).contains("secret"));

        SessionMessageEntity oversized = message(
                "message-large", "{" + "x".repeat(64 * 1024) + "}", "{malformed");
        OpenTaskEvidenceDTO rejected = mapper.mapEvidence(
                objectMapper, task, "agent-1", "ctx-1", List.of(oversized));
        assertFalse(rejected.getStructuredOutput().getAvailable());
    }

    @Test
    void classifiesOnlyExistingFailureSignalsAndPreservesBackendAliases() {
        assertEquals("PROVIDER_API", mapper.inferFailureStageFromText(
                "FAILED", null, null, "provider API unauthorized"));
        assertEquals("WORKER_TRANSPORT", mapper.inferFailureStageFromText(
                "FAILED", null, null, "worker stream timeout"));
        assertEquals("RUNTIME", mapper.inferFailureStageFromText(
                "FAILED", "codex-app-server-worker", null, "opaque failure"));
        assertEquals("DISPATCH", mapper.inferFailureStageFromText(
                "FAILED", "custom-provider", null, "opaque failure"));
        assertNull(mapper.inferFailureStageFromText(
                "RUNNING", "codex-app-server-worker", null, "opaque failure"));

        assertEquals("OPENAI_CODEX", mapper.workerBackendFromProviderType("codex-biz-worker"));
        assertEquals("OPENAI_CODEX_APP_SERVER",
                mapper.workerBackendFromProviderType("codex-app-server-worker"));
        assertEquals("GEMINI_CLI", mapper.workerBackendFromProviderType("gemini"));
        assertEquals("CUSTOM-PROVIDER", mapper.workerBackendFromProviderType(" custom-provider "));
        assertNull(mapper.workerBackendFromProviderType(null));
    }

    private SessionTaskEntity durableTask(String status) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setAgentId("agent-1");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setProviderType("CLAUDE_WORKER");
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 10, 2));
        return task;
    }

    private SessionMessageEntity message(String id, String content, String metadata) {
        SessionMessageEntity message = new SessionMessageEntity();
        message.setId(id);
        message.setTaskId("task-1");
        message.setSessionId("session-1");
        message.setRole("ASSISTANT");
        message.setContent(content);
        message.setMetadata(metadata);
        message.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 1));
        return message;
    }
}

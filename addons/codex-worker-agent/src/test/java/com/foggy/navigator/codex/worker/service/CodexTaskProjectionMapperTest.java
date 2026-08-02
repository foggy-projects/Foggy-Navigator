package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorCategory;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.agent.framework.diagnostic.ErrorRuntimePhase;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexTaskProjectionMapperTest {

    private final CodexTaskProjectionMapper mapper = new CodexTaskProjectionMapper();

    @Test
    void mapsCurrentFieldsFromExplicitInputsWithOneObservationAndDoesNotMutateEntity() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        CodexTaskEntity entity = fullEntity(observedAt);
        CodexTaskEntity unchanged = fullEntity(observedAt);
        ErrorEnvelope error = ErrorEnvelope.builder()
                .errorCode("RUNTIME_TIMEOUT")
                .message("safe timeout")
                .category(ErrorCategory.TIMEOUT)
                .runtimePhase(ErrorRuntimePhase.EVENT_STREAM)
                .recoverable(Boolean.TRUE)
                .diagnosticRef("diagnostic-1")
                .occurredAt(Instant.parse("2026-08-03T03:59:00Z"))
                .taskId("error-task")
                .providerType("envelope-provider")
                .runtimeType("envelope-runtime")
                .build();

        DispatchTaskDTO result = mapper.toDispatchTask(
                entity, "resolved-agent", "resolved-provider", "resolved-context", error, observedAt);

        assertAll(
                () -> assertEquals("task-1", result.getTaskId()),
                () -> assertEquals("worker-task-1", result.getWorkerTaskId()),
                () -> assertEquals("runtime-1", result.getRuntimeId()),
                () -> assertEquals(7, result.getRuntimeRevision()),
                () -> assertEquals("APP_SERVER", result.getRuntimeType()),
                () -> assertEquals("runtime-instance-1", result.getRuntimeInstanceId()),
                () -> assertEquals(13L, result.getRoutingEpoch()),
                () -> assertEquals("ACCEPTED", result.getRuntimeAcceptanceState()),
                () -> assertEquals("session-1", result.getSessionId()),
                () -> assertEquals("worker-1", result.getWorkerId()),
                () -> assertEquals("user-1", result.getUserId()),
                () -> assertEquals("resolved-agent", result.getAgentId()),
                () -> assertEquals("resolved-provider", result.getProviderType()),
                () -> assertEquals("raw prompt", result.getPrompt()),
                () -> assertEquals("/workspace/project", result.getCwd()),
                () -> assertEquals("directory-1", result.getDirectoryId()),
                () -> assertEquals("RUNNING", result.getStatus()),
                () -> assertEquals("codex-latest:high", result.getModel()),
                () -> assertEquals(new BigDecimal("1.250000"), result.getCostUsd()),
                () -> assertEquals(101L, result.getInputTokens()),
                () -> assertEquals(202L, result.getOutputTokens()),
                () -> assertEquals(303L, result.getDurationMs()),
                () -> assertEquals(4, result.getNumTurns()),
                () -> assertEquals("raw result", result.getResultText()),
                () -> assertEquals("raw error", result.getErrorMessage()),
                () -> assertEquals(9, result.getLastAckedSeq()),
                () -> assertEquals(observedAt.minusSeconds(299), result.getLastOutputAt()),
                () -> assertFalse(result.getResponseTimedOut()),
                () -> assertEquals(299L, result.getSilentForSeconds()),
                () -> assertEquals(300L, result.getResponseTimeoutThresholdSeconds()),
                () -> assertEquals("PLATFORM", result.getSource()),
                () -> assertEquals(observedAt.minusHours(1), result.getCreatedAt()),
                () -> assertEquals(1_785_726_000_123L, result.getCreatedAtEpochMs()),
                () -> assertEquals(observedAt.minusMinutes(1), result.getUpdatedAt()),
                () -> assertEquals("thread-1", result.getCodexThreadId()),
                () -> assertEquals("resolved-context", result.getContextId()));

        assertEquals(List.of(
                        "errorCode", "message", "category", "runtimePhase", "recoverable",
                        "diagnosticRef", "occurredAt", "taskId", "providerType", "runtimeType"),
                List.copyOf(result.getError().keySet()));
        assertSame(ErrorCategory.TIMEOUT, result.getError().get("category"));
        assertSame(ErrorRuntimePhase.EVENT_STREAM, result.getError().get("runtimePhase"));
        assertEquals("envelope-provider", result.getError().get("providerType"));

        assertAll(
                () -> assertNull(result.getParentSessionId()),
                () -> assertNull(result.getModelConfigId()),
                () -> assertNull(result.getStructuredOutput()),
                () -> assertNull(result.getSessionTaskCount()),
                () -> assertNull(result.getSessionTotalCostUsd()),
                () -> assertNull(result.getSessionInputTokens()),
                () -> assertNull(result.getSessionOutputTokens()),
                () -> assertNull(result.getSessionFirstPrompt()),
                () -> assertNull(result.getClaudeSessionId()),
                () -> assertNull(result.getGeminiSessionId()),
                () -> assertNull(result.getCheckpoints()),
                () -> assertNull(result.getFileCheckpointingEnabled()),
                () -> assertNull(result.getDirectoryName()));
        assertEquals(unchanged, entity);
    }

    @Test
    void keepsNullableCreationEpochAndNeverFallsBackIdentityProviderOrContext() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setStatus("running");
        entity.setErrorMessage("  raw failure  ");
        entity.setCreatedAt(observedAt.minusHours(1));
        entity.setCreatedAtEpochMs(null);
        entity.setResolvedAgentId("entity-agent");
        entity.setProviderType("entity-provider");
        entity.setContextId("entity-context");

        DispatchTaskDTO result = mapper.toDispatchTask(
                entity, null, null, null, null, observedAt);

        assertAll(
                () -> assertNull(result.getAgentId()),
                () -> assertNull(result.getProviderType()),
                () -> assertNull(result.getContextId()),
                () -> assertNull(result.getCreatedAtEpochMs()),
                () -> assertEquals("running", result.getStatus()),
                () -> assertEquals("  raw failure  ", result.getErrorMessage()),
                () -> assertNull(result.getError()));
        assertAll(
                () -> assertEquals("entity-agent", entity.getResolvedAgentId()),
                () -> assertEquals("entity-provider", entity.getProviderType()),
                () -> assertEquals("entity-context", entity.getContextId()));
    }

    @Test
    void computesRunningTimeoutAtExactBoundaryAndClampsFutureOrNonRunningTasks() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 3, 12, 0);

        DispatchTaskDTO at299 = timeoutProjection(
                "RUNNING", observedAt.minusSeconds(299), observedAt.minusHours(1), observedAt);
        DispatchTaskDTO at300FromCreation = timeoutProjection(
                "RUNNING", null, observedAt.minusSeconds(300), observedAt);
        DispatchTaskDTO future = timeoutProjection(
                "RUNNING", observedAt.plusSeconds(1), observedAt.minusHours(1), observedAt);
        DispatchTaskDTO nonRunning = timeoutProjection(
                "COMPLETED", observedAt.minusHours(1), observedAt.minusHours(2), observedAt);

        assertAll(
                () -> assertEquals(299L, at299.getSilentForSeconds()),
                () -> assertFalse(at299.getResponseTimedOut()),
                () -> assertEquals(300L, at300FromCreation.getSilentForSeconds()),
                () -> assertTrue(at300FromCreation.getResponseTimedOut()),
                () -> assertEquals(0L, future.getSilentForSeconds()),
                () -> assertFalse(future.getResponseTimedOut()),
                () -> assertEquals(0L, nonRunning.getSilentForSeconds()),
                () -> assertFalse(nonRunning.getResponseTimedOut()));
    }

    @Test
    void projectsErrorAllowlistWithStableOrderAndEmptyAllNullEnvelope() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        ErrorEnvelope partial = ErrorEnvelope.builder()
                .message("safe")
                .category(ErrorCategory.CONFIGURATION)
                .runtimePhase(ErrorRuntimePhase.REQUEST_VALIDATION)
                .recoverable(Boolean.FALSE)
                .build();

        DispatchTaskDTO partialResult = mapper.toDispatchTask(
                new CodexTaskEntity(), null, null, null, partial, observedAt);
        DispatchTaskDTO emptyResult = mapper.toDispatchTask(
                new CodexTaskEntity(), null, null, null, new ErrorEnvelope(), observedAt);

        assertEquals(List.of("message", "category", "runtimePhase", "recoverable"),
                List.copyOf(partialResult.getError().keySet()));
        assertSame(ErrorCategory.CONFIGURATION, partialResult.getError().get("category"));
        assertSame(ErrorRuntimePhase.REQUEST_VALIDATION, partialResult.getError().get("runtimePhase"));
        assertEquals(Boolean.FALSE, partialResult.getError().get("recoverable"));
        assertInstanceOf(LinkedHashMap.class, emptyResult.getError());
        assertTrue(emptyResult.getError().isEmpty());
    }

    @Test
    void mapsOnlyTheExistingCaseSensitiveInteractionStates() {
        for (String status : List.of("RUNNING", "PENDING", "CANCEL_REQUESTED")) {
            assertEquals("PROCESSING", mapper.interactionState(status), status);
        }
        for (String status : List.of(
                "COMPLETED", "FAILED", "ABORTED", "AWAITING_PERMISSION", "AWAITING_INPUT")) {
            assertEquals("AWAITING_REPLY", mapper.interactionState(status), status);
        }
        for (String status : List.of(
                "running", "CANCELLED", "CANCELED", "REJECTED", "TIMED_OUT", "UNKNOWN", "")) {
            assertNull(mapper.interactionState(status), status);
        }
        assertNull(mapper.interactionState(null));
    }

    private DispatchTaskDTO timeoutProjection(String status,
                                              LocalDateTime lastOutputAt,
                                              LocalDateTime createdAt,
                                              LocalDateTime observedAt) {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setStatus(status);
        entity.setLastOutputAt(lastOutputAt);
        entity.setCreatedAt(createdAt);
        return mapper.toDispatchTask(entity, null, null, null, null, observedAt);
    }

    private CodexTaskEntity fullEntity(LocalDateTime observedAt) {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setId(42L);
        entity.setTaskId("task-1");
        entity.setWorkerTaskId("worker-task-1");
        entity.setRuntimeId("runtime-1");
        entity.setRuntimeRevision(7);
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeInstanceId("runtime-instance-1");
        entity.setRoutingEpoch(13L);
        entity.setRuntimeAcceptanceState("ACCEPTED");
        entity.setRuntimeRequestHash("request-hash");
        entity.setRuntimeRequestCiphertext("request-ciphertext");
        entity.setSessionId("session-1");
        entity.setDirectoryId("directory-1");
        entity.setWorkerId("worker-1");
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setResolvedAgentId("entity-agent");
        entity.setContextId("entity-context");
        entity.setProviderType("entity-provider");
        entity.setCodexHomeKey("codex-home");
        entity.setPrivateAccountId("private-account");
        entity.setPrompt("raw prompt");
        entity.setCwd("/workspace/project");
        entity.setStatus("RUNNING");
        entity.setCodexThreadId("thread-1");
        entity.setModel("codex-latest:high");
        entity.setCostUsd(new BigDecimal("1.250000"));
        entity.setInputTokens(101L);
        entity.setOutputTokens(202L);
        entity.setDurationMs(303L);
        entity.setNumTurns(4);
        entity.setResultText("raw result");
        entity.setErrorMessage("raw error");
        entity.setLastAckedSeq(9);
        entity.setSource("PLATFORM");
        entity.setLastAliveAt(observedAt.minusSeconds(10));
        entity.setLastOutputAt(observedAt.minusSeconds(299));
        entity.setCreatedAt(observedAt.minusHours(1));
        entity.setCreatedAtEpochMs(1_785_726_000_123L);
        entity.setUpdatedAt(observedAt.minusMinutes(1));
        return entity;
    }
}

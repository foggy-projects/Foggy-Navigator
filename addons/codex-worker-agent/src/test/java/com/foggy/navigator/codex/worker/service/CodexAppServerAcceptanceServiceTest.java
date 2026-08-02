package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexAppServerAcceptanceServiceTest {

    @Test
    void automaticRecoveryAttemptMakesOnlyOneProviderAcceptanceCall() {
        CodexTaskRuntimeStateService stateService = mock(CodexTaskRuntimeStateService.class);
        CodexWorkerClient client = mock(CodexWorkerClient.class);
        when(client.createTask("task-1", Map.of("prompt", "x")))
                .thenReturn(Mono.error(new IllegalStateException("transport unavailable")));
        CodexAppServerAcceptanceService service = new CodexAppServerAcceptanceService(stateService);

        assertThrows(CodexAppServerAcceptanceService.UnknownException.class,
                () -> service.acceptForRecoveryAttempt(
                        client, "task-1", Map.of("prompt", "x")));

        verify(client, times(1)).createTask("task-1", Map.of("prompt", "x"));
        verify(stateService, never()).recordAccepted("task-1", "task-1");
    }

    @Test
    void rejectsAWorkerTaskIdThatDiffersFromTheIdempotencyKey() {
        CodexTaskRuntimeStateService stateService = mock(CodexTaskRuntimeStateService.class);
        CodexWorkerClient client = mock(CodexWorkerClient.class);
        CodexTaskAcceptanceDTO acceptance = new CodexTaskAcceptanceDTO();
        acceptance.setTaskId("another-task");
        acceptance.setStatus("accepted");
        when(client.createTask("task-1", Map.of("prompt", "x")))
                .thenReturn(Mono.just(acceptance));
        CodexAppServerAcceptanceService service = new CodexAppServerAcceptanceService(stateService);

        CodexAppServerAcceptanceService.RejectedException error = assertThrows(
                CodexAppServerAcceptanceService.RejectedException.class,
                () -> service.accept(client, "task-1", Map.of("prompt", "x")));

        assertTrue(error.getMessage().contains("CODEX_RUNTIME_TASK_ID_MISMATCH"));
        assertEquals("CODEX_RUNTIME_TASK_ID_MISMATCH", error.getWorkerErrorCode());
        verify(stateService, never()).recordAccepted("task-1", "another-task");
    }

    @Test
    void mapsActiveThreadConflictToStableBusinessCode() {
        CodexTaskRuntimeStateService stateService = mock(CodexTaskRuntimeStateService.class);
        CodexWorkerClient client = mock(CodexWorkerClient.class);
        WebClientResponseException conflict = WebClientResponseException.create(
                409, "Conflict", HttpHeaders.EMPTY,
                "{\"error\":\"APP_SERVER_THREAD_ACTIVE\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(client.createTask("task-1", Map.of("prompt", "continue")))
                .thenReturn(Mono.error(conflict));
        CodexAppServerAcceptanceService service = new CodexAppServerAcceptanceService(stateService);

        CodexAppServerAcceptanceService.RejectedException error = assertThrows(
                CodexAppServerAcceptanceService.RejectedException.class,
                () -> service.accept(client, "task-1", Map.of("prompt", "continue")));

        assertTrue(error.getMessage().contains("CODEX_RUNTIME_THREAD_ACTIVE"));
        assertEquals("CODEX_RUNTIME_THREAD_ACTIVE", error.getWorkerErrorCode());
        verify(stateService, never()).recordAccepted("task-1", "task-1");
    }

    @Test
    void mapsContractIdempotencyConflictToStableBusinessCode() {
        CodexAppServerAcceptanceService.RejectedException error = reject(
                409, "IDEMPOTENCY_KEY_CONFLICT");

        assertEquals(
                "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: app-server rejected a changed payload",
                error.getMessage());
        assertEquals("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT", error.getWorkerErrorCode());
    }

    @Test
    void preservesOnlyContractWorkerCodesForRejectedRequests() {
        Map<String, Integer> contractCodes = Map.ofEntries(
                Map.entry("INVALID_JSON_BODY", 400),
                Map.entry("RUNTIME_INSTANCE_MISMATCH", 409),
                Map.entry("UNSUPPORTED_REQUEST_FIELD", 400),
                Map.entry("UNSUPPORTED_MAX_TURNS", 400),
                Map.entry("UNSUPPORTED_ENV_VARS", 400),
                Map.entry("UNSUPPORTED_CODEX_CONFIG_KEY", 400),
                Map.entry("INVALID_CODEX_CONFIG_VALUE", 400),
                Map.entry("UNSUPPORTED_APPROVAL_POLICY", 400),
                Map.entry("UNSUPPORTED_ATTACHMENTS", 400),
                Map.entry("UNSUPPORTED_BUSINESS_RUNTIME_CONTEXT", 400),
                Map.entry("UNSUPPORTED_ADDITIONAL_DIRECTORIES", 400),
                Map.entry("UNSUPPORTED_CODEX_MODEL", 400),
                Map.entry("WORKING_DIRECTORY_NOT_ALLOWED", 403),
                Map.entry("ADDITIONAL_DIRECTORY_NOT_ALLOWED", 403),
                Map.entry("APP_SERVER_TASK_QUEUE_FULL", 429));

        contractCodes.forEach((code, status) -> {
            CodexAppServerAcceptanceService.RejectedException error = reject(status, code);

            assertEquals("CODEX_RUNTIME_REQUEST_REJECTED: " + code, error.getMessage());
            assertEquals(code, error.getWorkerErrorCode());
        });
    }

    @Test
    void doesNotExposeUnstructuredWorkerErrorDetails() {
        assertOpaqueRejection(403, "Invalid token: secret-value");
        assertOpaqueRejection(403, "AKIAIOSFODNN7EXAMPLE");
        assertOpaqueRejection(409, "UNKNOWN_SAFE_LOOKING_CODE");
    }

    private CodexAppServerAcceptanceService.RejectedException reject(int status, String workerError) {
        CodexTaskRuntimeStateService stateService = mock(CodexTaskRuntimeStateService.class);
        CodexWorkerClient client = mock(CodexWorkerClient.class);
        WebClientResponseException response = WebClientResponseException.create(
                status, "Rejected", HttpHeaders.EMPTY,
                ("{\"error\":\"" + workerError + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(client.createTask("task-1", Map.of("prompt", "x")))
                .thenReturn(Mono.error(response));
        CodexAppServerAcceptanceService service = new CodexAppServerAcceptanceService(stateService);

        CodexAppServerAcceptanceService.RejectedException error = assertThrows(
                CodexAppServerAcceptanceService.RejectedException.class,
                () -> service.accept(client, "task-1", Map.of("prompt", "x")));
        verify(stateService, never()).recordAccepted("task-1", "task-1");
        return error;
    }

    private void assertOpaqueRejection(int status, String workerError) {
        CodexAppServerAcceptanceService.RejectedException error = reject(status, workerError);

        assertEquals("CODEX_RUNTIME_REQUEST_REJECTED: app-server returned HTTP " + status,
                error.getMessage());
        assertFalse(error.getMessage().contains(workerError));
        assertNull(error.getWorkerErrorCode());
    }
}

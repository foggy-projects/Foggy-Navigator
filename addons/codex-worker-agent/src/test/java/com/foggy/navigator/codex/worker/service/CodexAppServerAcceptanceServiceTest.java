package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexAppServerAcceptanceServiceTest {

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
        verify(stateService, never()).recordAccepted("task-1", "task-1");
    }
}

package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CodexAppServerAcceptanceService {

    private final CodexTaskRuntimeStateService taskRuntimeStateService;

    public String accept(CodexWorkerClient client, String taskId, Map<String, Object> requestBody) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                CodexTaskAcceptanceDTO acceptance = client.createTask(taskId, requestBody)
                        .block(Duration.ofSeconds(20));
                if (acceptance == null || acceptance.getTaskId() == null
                        || acceptance.getTaskId().isBlank()) {
                    throw new IllegalStateException("app-server returned no task_id");
                }
                if (!taskId.equals(acceptance.getTaskId())) {
                    throw new RejectedException(
                            "CODEX_RUNTIME_TASK_ID_MISMATCH: app-server returned another task id", null);
                }
                taskRuntimeStateService.recordAccepted(taskId, acceptance.getTaskId());
                return acceptance.getTaskId();
            } catch (Exception e) {
                if (e instanceof RejectedException rejected) throw rejected;
                if (e instanceof CodexTaskRuntimeStateService.AcceptanceCancelledException cancelled) {
                    throw cancelled;
                }
                WebClientResponseException response = findResponseException(e);
                if (response != null && response.getStatusCode().value() == 409) {
                    throw new RejectedException(
                            "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: app-server rejected a changed payload", e);
                }
                if (response != null && response.getStatusCode().is4xxClientError()) {
                    throw new RejectedException(
                            "CODEX_RUNTIME_REQUEST_REJECTED: app-server returned HTTP "
                                    + response.getStatusCode().value(), e);
                }
                lastError = e;
                if (attempt == 3) break;
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    lastError = interrupted;
                    break;
                }
            }
        }
        throw new UnknownException(
                "CODEX_RUNTIME_ACCEPTANCE_UNKNOWN: app-server acceptance could not be confirmed",
                lastError);
    }

    private WebClientResponseException findResponseException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException response) return response;
            current = current.getCause();
        }
        return null;
    }

    public static final class UnknownException extends RuntimeException {
        private UnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class RejectedException extends RuntimeException {
        private RejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

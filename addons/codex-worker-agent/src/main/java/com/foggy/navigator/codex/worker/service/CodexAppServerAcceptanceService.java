package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import com.foggy.navigator.common.util.ProviderStateCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CodexAppServerAcceptanceService {

    private static final Set<String> SAFE_TASK_REJECTION_CODES = Set.of(
            "INVALID_JSON_BODY",
            "RUNTIME_INSTANCE_MISMATCH",
            "UNSUPPORTED_REQUEST_FIELD",
            "UNSUPPORTED_MAX_TURNS",
            "UNSUPPORTED_ENV_VARS",
            "UNSUPPORTED_CODEX_CONFIG_KEY",
            "INVALID_CODEX_CONFIG_VALUE",
            "UNSUPPORTED_APPROVAL_POLICY",
            "UNSUPPORTED_ATTACHMENTS",
            "UNSUPPORTED_BUSINESS_RUNTIME_CONTEXT",
            "UNSUPPORTED_ADDITIONAL_DIRECTORIES",
            "UNSUPPORTED_CODEX_MODEL",
            "WORKING_DIRECTORY_NOT_ALLOWED",
            "ADDITIONAL_DIRECTORY_NOT_ALLOWED",
            "IDEMPOTENCY_KEY_CONFLICT",
            "APP_SERVER_TASK_QUEUE_FULL",
            "APP_SERVER_THREAD_ACTIVE");

    private final CodexTaskRuntimeStateService taskRuntimeStateService;

    public String accept(CodexWorkerClient client, String taskId, Map<String, Object> requestBody) {
        return accept(client, taskId, requestBody, 3);
    }

    /** One provider call for one policy-budgeted automatic recovery attempt. */
    String acceptForRecoveryAttempt(
            CodexWorkerClient client, String taskId, Map<String, Object> requestBody) {
        return accept(client, taskId, requestBody, 1);
    }

    private String accept(
            CodexWorkerClient client,
            String taskId,
            Map<String, Object> requestBody,
            int maxAttempts) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                CodexTaskAcceptanceDTO acceptance = client.createTask(taskId, requestBody)
                        .block(Duration.ofSeconds(20));
                if (acceptance == null || acceptance.getTaskId() == null
                        || acceptance.getTaskId().isBlank()) {
                    throw new IllegalStateException("app-server returned no task_id");
                }
                if (!taskId.equals(acceptance.getTaskId())) {
                    throw new RejectedException(
                            "CODEX_RUNTIME_TASK_ID_MISMATCH: app-server returned another task id", null,
                            "CODEX_RUNTIME_TASK_ID_MISMATCH");
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
                    String errorCode = responseErrorCode(response);
                    if ("APP_SERVER_THREAD_ACTIVE".equals(errorCode)) {
                        throw new RejectedException(
                                "CODEX_RUNTIME_THREAD_ACTIVE: Codex thread already has an active turn", e,
                                "CODEX_RUNTIME_THREAD_ACTIVE");
                    }
                    if ("IDEMPOTENCY_KEY_CONFLICT".equals(errorCode)) {
                        throw new RejectedException(
                                "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: app-server rejected a changed payload", e,
                                "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT");
                    }
                    if (errorCode != null) {
                        throw rejectedRequest(errorCode, e);
                    }
                    throw rejectedHttp(response, e);
                }
                if (response != null && response.getStatusCode().is4xxClientError()) {
                    String errorCode = responseErrorCode(response);
                    if (errorCode != null) {
                        throw rejectedRequest(errorCode, e);
                    }
                    throw rejectedHttp(response, e);
                }
                lastError = e;
                if (attempt == maxAttempts) break;
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

    private String responseErrorCode(WebClientResponseException response) {
        Map<String, Object> body = ProviderStateCodec.parseObject(response.getResponseBodyAsString());
        for (String key : new String[]{"error_code", "error", "code"}) {
            Object value = body.get(key);
            if (value != null && SAFE_TASK_REJECTION_CODES.contains(value.toString())) {
                return value.toString();
            }
        }
        return null;
    }

    private RejectedException rejectedRequest(String errorCode, Throwable cause) {
        return new RejectedException(
                "CODEX_RUNTIME_REQUEST_REJECTED: " + errorCode, cause, errorCode);
    }

    private RejectedException rejectedHttp(WebClientResponseException response, Throwable cause) {
        return new RejectedException(
                "CODEX_RUNTIME_REQUEST_REJECTED: app-server returned HTTP "
                        + response.getStatusCode().value(), cause);
    }

    public static final class UnknownException extends RuntimeException {
        private UnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class RejectedException extends RuntimeException {
        private final String workerErrorCode;

        private RejectedException(String message, Throwable cause) {
            this(message, cause, null);
        }

        private RejectedException(String message, Throwable cause, String workerErrorCode) {
            super(message, cause);
            this.workerErrorCode = workerErrorCode;
        }

        public String getWorkerErrorCode() {
            return workerErrorCode;
        }
    }
}

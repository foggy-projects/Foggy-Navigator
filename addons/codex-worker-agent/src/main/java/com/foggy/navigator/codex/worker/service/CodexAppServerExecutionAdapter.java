package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Narrow execution boundary for a Codex AppServer task's persisted identity.
 *
 * <p>The adapter binds one Navigator task to its immutable seven-field runtime
 * affinity and to the exact client resolved by the affinity adapter. Provider
 * calls never accept a task id, worker task id, or client from the caller after
 * that bind. Relay policy, scheduling, leases, locking and terminal decisions
 * deliberately remain outside this class.
 */
@Component
@RequiredArgsConstructor
public class CodexAppServerExecutionAdapter {

    private final CodexAppServerRuntimeAffinityAdapter runtimeAffinityAdapter;
    private final CodexAppServerAcceptanceService acceptanceService;
    private final CodexTaskRuntimeStateService runtimeStateService;

    AppServerExecution bind(
            String navigatorTaskId,
            String persistedWorkerTaskId,
            CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity) {
        String boundTaskId = requireText(
                navigatorTaskId, "CODEX_RUNTIME_TASK_ID_MISSING");
        if (persistedWorkerTaskId != null && persistedWorkerTaskId.isBlank()) {
            throw new IllegalStateException("CODEX_RUNTIME_WORKER_TASK_ID_INVALID");
        }
        CodexAppServerRuntimeAffinityAdapter.BoundRuntime boundRuntime =
                runtimeAffinityAdapter.resolveBound(affinity);
        CodexWorkerClient client = runtimeAffinityAdapter.client(boundRuntime);
        return new AppServerExecution(
                boundTaskId, persistedWorkerTaskId, affinity, client);
    }

    CodexWorkerClient client(AppServerExecution execution) {
        return requireExecution(execution).client;
    }

    AppServerExecution acceptInitial(
            AppServerExecution execution, Map<String, Object> requestBody) {
        AppServerExecution bound = requireUnaccepted(execution);
        try {
            runtimeStateService.prepareAcceptance(
                    bound.navigatorTaskId, requestBody);
            return accepted(bound, acceptanceService.accept(
                    bound.client, bound.navigatorTaskId, requestBody));
        } catch (CodexAppServerAcceptanceService.UnknownException error) {
            markAcceptanceUnknownBestEffort(bound.navigatorTaskId, error);
            throw error;
        }
    }

    void recoverAcceptance(
            AppServerExecution execution, boolean automatic) {
        AppServerExecution bound = requireUnaccepted(execution);
        Map<String, Object> requestBody =
                runtimeStateService.loadPreparedRequest(bound.navigatorTaskId);
        try {
            if (automatic) {
                acceptanceService.acceptForRecoveryAttempt(
                        bound.client, bound.navigatorTaskId, requestBody);
            } else {
                acceptanceService.accept(
                        bound.client, bound.navigatorTaskId, requestBody);
            }
        } catch (CodexAppServerAcceptanceService.UnknownException error) {
            markAcceptanceUnknownBestEffort(bound.navigatorTaskId, error);
            throw error;
        }
    }

    Flux<ServerSentEvent<String>> subscribe(
            AppServerExecution execution, int ackSeq) {
        AppServerExecution bound = requireAccepted(execution);
        requireSubscriptionAllowed(bound.navigatorTaskId);
        return bound.client.subscribeToTask(bound.workerTaskId, ackSeq);
    }

    Flux<ServerSentEvent<String>> subscribe(
            AppServerExecution execution,
            int ackSeq,
            Runnable connectionSettledCallback) {
        AppServerExecution bound = requireAccepted(execution);
        requireSubscriptionAllowed(bound.navigatorTaskId);
        return bound.client.subscribeToTask(
                bound.workerTaskId, ackSeq, connectionSettledCallback);
    }

    RemoteTaskStatus status(AppServerExecution execution) {
        AppServerExecution bound = requireAccepted(execution);
        var statusMono = bound.client.getTaskStatus(bound.workerTaskId);
        if (statusMono == null) {
            return null;
        }
        Map<String, Object> body = statusMono.block(Duration.ofSeconds(10));
        if (body == null) {
            return null;
        }
        String returnedTaskId = stringField(body, "task_id");
        if (!bound.workerTaskId.equals(returnedTaskId)) {
            throw new IllegalStateException("CODEX_RUNTIME_STATUS_TASK_MISMATCH");
        }
        return new RemoteTaskStatus(
                stringField(body, "status"),
                stringField(body, "outcome"),
                stringField(body, "thread_id"),
                stringField(body, "model"),
                stringField(body, "error_code"),
                safeObjectMap(body.get("pending_interaction")));
    }

    private AppServerExecution accepted(
            AppServerExecution execution, String workerTaskId) {
        String acceptedTaskId = requireText(
                workerTaskId, "CODEX_RUNTIME_WORKER_TASK_ID_MISSING");
        if (!execution.navigatorTaskId.equals(acceptedTaskId)) {
            throw new IllegalStateException("CODEX_RUNTIME_TASK_ID_MISMATCH");
        }
        return execution.withWorkerTaskId(acceptedTaskId);
    }

    private void requireSubscriptionAllowed(String navigatorTaskId) {
        if (!runtimeStateService.markSubscribed(navigatorTaskId)) {
            throw new SubscriptionDeniedException();
        }
    }

    private void markAcceptanceUnknownBestEffort(
            String navigatorTaskId,
            CodexAppServerAcceptanceService.UnknownException acceptanceError) {
        try {
            runtimeStateService.markAcceptanceUnknown(navigatorTaskId);
        } catch (RuntimeException stateError) {
            acceptanceError.addSuppressed(stateError);
        }
    }

    private AppServerExecution requireExecution(AppServerExecution execution) {
        return Objects.requireNonNull(
                execution, "App-server execution binding is required");
    }

    private AppServerExecution requireUnaccepted(AppServerExecution execution) {
        AppServerExecution bound = requireExecution(execution);
        if (bound.workerTaskId != null) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_CONTINUATION_RECREATE_DENIED");
        }
        return bound;
    }

    private AppServerExecution requireAccepted(AppServerExecution execution) {
        AppServerExecution bound = requireExecution(execution);
        if (bound.workerTaskId == null || bound.workerTaskId.isBlank()) {
            throw new IllegalStateException("CODEX_RUNTIME_WORKER_TASK_ID_MISSING");
        }
        return bound;
    }

    private String requireText(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(errorCode);
        }
        return value;
    }

    private String stringField(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null ? value.toString() : null;
    }

    private Map<String, Object> safeObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> {
            if (key != null) {
                result.put(key.toString(), entryValue);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Controlled proof of one persisted Task-to-runtime execution binding.
     * Its private constructor prevents callers from supplying another task id,
     * worker task id, affinity or client to continuation operations.
     */
    static final class AppServerExecution {
        private final String navigatorTaskId;
        private final String workerTaskId;
        private final CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity;
        private final CodexWorkerClient client;

        private AppServerExecution(
                String navigatorTaskId,
                String workerTaskId,
                CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity,
                CodexWorkerClient client) {
            this.navigatorTaskId = navigatorTaskId;
            this.workerTaskId = workerTaskId;
            this.affinity = affinity;
            this.client = client;
        }

        String navigatorTaskId() {
            return navigatorTaskId;
        }

        String workerTaskId() {
            return workerTaskId;
        }

        CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity() {
            return affinity;
        }

        private AppServerExecution withWorkerTaskId(String acceptedWorkerTaskId) {
            return new AppServerExecution(
                    navigatorTaskId, acceptedWorkerTaskId, affinity, client);
        }
    }

    record RemoteTaskStatus(
            String status,
            String outcome,
            String threadId,
            String model,
            String errorCode,
            Map<String, Object> pendingInteraction) {
    }

    static final class SubscriptionDeniedException extends IllegalStateException {
        private SubscriptionDeniedException() {
            super("CODEX_RUNTIME_SUBSCRIPTION_DENIED");
        }
    }
}

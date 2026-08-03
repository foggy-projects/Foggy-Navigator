package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

/**
 * Narrow execution boundary for the two legacy Codex SDK provider identities.
 *
 * <p>The relay remains responsible for request construction, lifecycle admission,
 * persistence, recovery and terminal policy. This adapter only proves the exact
 * persisted SDK affinity, resolves its Worker client and forwards execution.
 */
@Component
@RequiredArgsConstructor
public class CodexSdkExecutionAdapter {

    private static final String SDK_PROVIDER = "codex-worker";
    private static final String BIZ_PROVIDER = "codex-biz-worker";
    private static final String LEGACY_RUNTIME_PREFIX = "legacy-sdk:";

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;

    SdkExecution bind(String providerType, String workerId, CodexRuntimeBinding runtime) {
        if (!SDK_PROVIDER.equals(providerType) && !BIZ_PROVIDER.equals(providerType)) {
            throw new IllegalArgumentException("CODEX_SDK_EXECUTION_PROVIDER_UNSUPPORTED");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("CODEX_SDK_EXECUTION_WORKER_REQUIRED");
        }
        if (runtime == null
                || runtime.getRuntimeType() != CodexRuntimeType.SDK_EXEC
                || !workerId.equals(runtime.getWorkerId())
                || !(LEGACY_RUNTIME_PREFIX + workerId).equals(runtime.getRuntimeId())) {
            throw new IllegalStateException("CODEX_SDK_EXECUTION_AFFINITY_MISMATCH");
        }

        CodexConfig config = workerManagementFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Codex not configured for worker: " + workerId);
        }
        CodexWorkerClient client = clientFactory.getOrCreate(
                workerId + ":codex", config.getBaseUrl(), config.getAuthToken());
        return new SdkExecution(providerType, workerId, runtime, client);
    }

    CodexWorkerClient client(SdkExecution execution) {
        return requireExecution(execution).client;
    }

    Flux<ServerSentEvent<String>> streamQuery(
            SdkExecution execution, Map<String, Object> requestBody) {
        return requireExecution(execution).client.streamQuery(requestBody);
    }

    Flux<ServerSentEvent<String>> subscribe(
            SdkExecution execution, String workerTaskId, int ackSeq) {
        return requireExecution(execution).client.subscribeToTask(workerTaskId, ackSeq);
    }

    Flux<ServerSentEvent<String>> subscribe(
            SdkExecution execution, String workerTaskId, int ackSeq,
            Runnable connectionSettledCallback) {
        return requireExecution(execution).client.subscribeToTask(
                workerTaskId, ackSeq, connectionSettledCallback);
    }

    private SdkExecution requireExecution(SdkExecution execution) {
        return Objects.requireNonNull(execution, "SDK execution binding is required");
    }

    static final class SdkExecution {
        private final String providerType;
        private final String workerId;
        private final CodexRuntimeBinding runtime;
        private final CodexWorkerClient client;

        private SdkExecution(String providerType, String workerId,
                             CodexRuntimeBinding runtime, CodexWorkerClient client) {
            this.providerType = providerType;
            this.workerId = workerId;
            this.runtime = runtime;
            this.client = client;
        }

        String providerType() {
            return providerType;
        }

        String workerId() {
            return workerId;
        }

        CodexRuntimeBinding runtime() {
            return runtime;
        }
    }
}

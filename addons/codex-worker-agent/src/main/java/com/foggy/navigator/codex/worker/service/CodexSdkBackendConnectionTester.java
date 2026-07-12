package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.util.CodexModelBackendPolicy;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.config.WorkerBackendConnectionTester;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class CodexSdkBackendConnectionTester implements WorkerBackendConnectionTester {

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;

    public CodexSdkBackendConnectionTester(
            @Lazy WorkerManagementFacade workerManagementFacade,
            CodexWorkerClientFactory clientFactory) {
        this.workerManagementFacade = workerManagementFacade;
        this.clientFactory = clientFactory;
    }

    @Override
    public String getWorkerBackend() {
        return ProviderRouteRegistry.BACKEND_OPENAI_CODEX;
    }

    @Override
    public boolean supportsWorker(String workerId) {
        CodexConfig config = workerManagementFacade.getCodexConfig(workerId);
        return config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    @Override
    public boolean supportsWorker(String workerId, String modelName) {
        try {
            CodexModelBackendPolicy.validateModel(getWorkerBackend(), modelName);
            return supportsWorker(workerId);
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    @Override
    public String testConnection(String userId, String tenantId, String workerId, String modelName) {
        CodexModelBackendPolicy.validateModel(getWorkerBackend(), modelName);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, workerId);
        CodexConfig config = workerManagementFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "CODEX_SDK_WORKER_NOT_CONFIGURED: " + workerId);
        }
        CodexWorkerClient client = clientFactory.getOrCreate(
                workerId + ":codex", config.getBaseUrl(), config.getAuthToken());
        Map<String, Object> health = client.healthCheck().block(Duration.ofSeconds(10));
        if (health == null) {
            throw new IllegalStateException("CODEX_SDK_WORKER_UNAVAILABLE");
        }
        return "Codex SDK Worker READY";
    }
}

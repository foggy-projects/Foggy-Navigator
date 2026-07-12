package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.common.util.CodexModelBackendPolicy;
import com.foggy.navigator.spi.config.WorkerBackendConnectionTester;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class CodexAppServerBackendConnectionTester implements WorkerBackendConnectionTester {

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexAppServerEndpointRepository endpointRepository;
    private final CodexRuntimeRegistryService runtimeRegistryService;

    public CodexAppServerBackendConnectionTester(
            @Lazy WorkerManagementFacade workerManagementFacade,
            CodexAppServerEndpointRepository endpointRepository,
            CodexRuntimeRegistryService runtimeRegistryService) {
        this.workerManagementFacade = workerManagementFacade;
        this.endpointRepository = endpointRepository;
        this.runtimeRegistryService = runtimeRegistryService;
    }

    @Override
    public String getWorkerBackend() {
        return ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER;
    }

    @Override
    public boolean supportsWorker(String workerId) {
        return !endpointRepository.findByWorkerIdOrderByUpdatedAtDesc(workerId).isEmpty();
    }

    @Override
    public boolean supportsWorker(String workerId, String modelName) {
        try {
            CodexModelBackendPolicy.validateModel(getWorkerBackend(), modelName);
            CodexRuntimeAvailabilityDTO availability =
                    runtimeRegistryService.availability(workerId, modelName);
            return availability != null && Boolean.TRUE.equals(availability.getModelAvailable());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public boolean supportsWorkerConfiguration(String workerId, String modelName) {
        try {
            CodexModelBackendPolicy.validateModel(getWorkerBackend(), modelName);
            CodexRuntimeAvailabilityDTO availability =
                    runtimeRegistryService.availability(workerId, modelName);
            return availability != null && Boolean.TRUE.equals(availability.getModelSupported());
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    @Override
    public String testConnection(String userId, String tenantId, String workerId, String modelName) {
        CodexModelBackendPolicy.validateModel(getWorkerBackend(), modelName);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, workerId);
        return runtimeRegistryService.testEndpointConnection(workerId, modelName);
    }
}

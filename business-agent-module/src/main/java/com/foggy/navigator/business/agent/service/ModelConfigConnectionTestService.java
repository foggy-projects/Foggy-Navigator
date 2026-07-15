package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.util.CodexModelBackendPolicy;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.config.WorkerBackendConnectionTester;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ModelConfigConnectionTestService {

    private final LlmModelManager llmModelManager;
    private final List<WorkerBackendConnectionTester> workerBackendConnectionTesters;

    public String test(String actorId, String tenantId, ClientAppModelConfigForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getModelName(), "modelName is required");
        String backend = canonicalBackend(form.getWorkerBackend());
        CodexModelBackendPolicy.validate(backend, form.getModelName(), form.getAvailableModels());

        Optional<WorkerBackendConnectionTester> tester = findTester(backend);
        if (tester.isPresent()) {
            requireText(form.getWorkerId(), "workerId is required for " + backend + " connection test");
            return tester.get().testConnection(actorId, tenantId, form.getWorkerId().trim(), form.getModelName().trim());
        }
        if (ProviderRouteRegistry.isManagedCredentialOptionalBackend(backend)) {
            throw new IllegalStateException("WORKER_BACKEND_TESTER_UNAVAILABLE: " + backend);
        }
        requireText(form.getBaseUrl(), "baseUrl is required");
        requireText(form.getApiKey(), "apiKey is required");
        return llmModelManager.testConnection(
                form.getBaseUrl().trim(), form.getApiKey().trim(), form.getModelName().trim());
    }

    public String testSaved(String actorId, String tenantId, LlmModelConfigDTO model, String workerId) {
        if (model == null) {
            throw new IllegalArgumentException("model config is required");
        }
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setWorkerBackend(model.getWorkerBackend());
        form.setWorkerId(firstNonBlank(workerId,
                model.getAllowedWorkerIds() != null && !model.getAllowedWorkerIds().isEmpty()
                        ? model.getAllowedWorkerIds().get(0) : null));
        form.setModelName(model.getModelName());
        form.setAvailableModels(model.getAvailableModels());
        form.setBaseUrl(model.getBaseUrl());
        form.setApiKey(llmModelManager.getDecryptedApiKey(model.getId()));
        return test(actorId, tenantId, form);
    }

    private Optional<WorkerBackendConnectionTester> findTester(String backend) {
        if (!StringUtils.hasText(backend)) {
            return Optional.empty();
        }
        return workerBackendConnectionTesters.stream()
                .filter(candidate -> backend.equals(canonicalBackend(candidate.getWorkerBackend())))
                .findFirst();
    }

    private String canonicalBackend(String workerBackend) {
        if (!StringUtils.hasText(workerBackend)) {
            return null;
        }
        return ProviderRouteRegistry.canonicalWorkerBackend(workerBackend)
                .orElseThrow(() -> new IllegalArgumentException("unsupported workerBackend: " + workerBackend));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}

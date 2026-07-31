package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePortResolver;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
public class CodexWorkerLifecyclePortResolver
        implements WorkerLifecyclePortResolver {
    private final WorkerManagementFacade workers;
    private final ObjectMapper objectMapper;

    public CodexWorkerLifecyclePortResolver(
            WorkerManagementFacade workers, ObjectMapper objectMapper) {
        this.workers = workers;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WorkerLifecyclePort> resolve(String physicalWorkerId) {
        CodexConfig config = workers.getCodexConfig(physicalWorkerId);
        if (config == null || blank(config.getBaseUrl()) || blank(config.getAuthToken())) {
            return Optional.empty();
        }
        return Optional.of(new CodexWorkerLifecycleHttpAdapter(
                physicalWorkerId,
                config.getBaseUrl(),
                config.getAuthToken(),
                objectMapper));
    }

    @Override
    public Set<String> discoverShadowWorkers() {
        return workers.listConfiguredCodexLifecycleWorkerIds();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

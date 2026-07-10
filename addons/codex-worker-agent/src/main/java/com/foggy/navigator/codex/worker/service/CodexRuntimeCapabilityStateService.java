package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists capability probe results without holding a database transaction during network I/O. */
@Service
@RequiredArgsConstructor
public class CodexRuntimeCapabilityStateService {

    private final CodexRuntimeRepository runtimeRepository;

    @Transactional
    public CodexRuntimeEntity updateLocked(String runtimeId, int revision, RuntimeUpdater updater) {
        CodexRuntimeEntity entity = runtimeRepository
                .findByRuntimeIdAndRevisionForUpdate(runtimeId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime revision not found: " + runtimeId + "@" + revision));
        updater.update(entity);
        return runtimeRepository.save(entity);
    }

    @FunctionalInterface
    public interface RuntimeUpdater {
        void update(CodexRuntimeEntity entity);
    }
}

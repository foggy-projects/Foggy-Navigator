package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.config.CrossProjectTaskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single server-owned gate for every CrossProjectTask mutation ingress.
 */
@Component
@RequiredArgsConstructor
public class CrossProjectMutationGate {

    private final CrossProjectTaskProperties properties;

    public boolean isEnabled() {
        return properties.isMutationsEnabled();
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new CrossProjectMutationRetiredException();
        }
    }
}

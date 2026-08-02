package com.foggy.navigator.claude.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Compatibility switch for the retired CrossProjectTask mutation surface.
 *
 * <p>Read-only access remains available regardless of this switch. Mutations
 * are disabled by default and must be explicitly re-enabled for rollback.</p>
 */
@ConfigurationProperties(prefix = "navigator.cross-project-task")
public class CrossProjectTaskProperties {

    private boolean mutationsEnabled;

    public boolean isMutationsEnabled() {
        return mutationsEnabled;
    }

    public void setMutationsEnabled(boolean mutationsEnabled) {
        this.mutationsEnabled = mutationsEnabled;
    }
}

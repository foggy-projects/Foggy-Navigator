package com.foggy.navigator.claude.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level switch for Navigator's public Open API surface.
 *
 * <p>The default remains internal-only. Enabling this switch opens only the
 * HTTP routing surface; it does not assert provider or production readiness.</p>
 */
@ConfigurationProperties(prefix = "navigator.external")
public class ExternalSurfaceProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

package com.foggy.navigator.business.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls strict Worker authentication on the internal Worker Gateway.
 *
 * <p>The default remains internal-development compatible. Enabling the
 * external profile requires every HTTP request to present the complete
 * Worker principal header set in addition to its task-scoped token.</p>
 */
@ConfigurationProperties(prefix = "navigator.worker-gateway")
public class WorkerGatewayProperties {

    private boolean externalEnabled;

    public boolean isExternalEnabled() {
        return externalEnabled;
    }

    public void setExternalEnabled(boolean externalEnabled) {
        this.externalEnabled = externalEnabled;
    }
}

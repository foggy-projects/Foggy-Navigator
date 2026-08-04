package com.foggy.navigator.workbench.fap.config;

import com.foggy.agent.contract.worker.v1alpha1.EnvironmentClass;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Personal-canary gate and server-side FAP credentials.
 *
 * <p>This configuration must never be exposed through a controller or browser bundle. Packaging
 * the module does not enable it; both {@code enabled=true} and an owner allowlist match are
 * required before any FAP mutation is admitted.
 */
@ConfigurationProperties("navigator.workbench.fap")
public class WorkbenchFapProperties {
    private boolean enabled;
    private Set<String> ownerUserIds = new LinkedHashSet<>();
    private URI accessBaseUri = URI.create("http://127.0.0.1:4860");
    private URI runtimeBaseUri = URI.create("http://127.0.0.1:4850");
    private String callerApplicationRef = "navigator-workbench";
    private String accessBearerToken;
    private String runtimeBearerToken;
    private String internalPrincipalPrefix = "navigator-user:";
    private EnvironmentClass environmentClass = EnvironmentClass.DEV;
    private int timeoutSeconds = 15;

    public boolean isEligible(String userId) {
        return enabled && userId != null && ownerUserIds.contains(userId);
    }

    public String internalPrincipalRef(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return internalPrincipalPrefix + userId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getOwnerUserIds() {
        return Set.copyOf(ownerUserIds);
    }

    public void setOwnerUserIds(Set<String> ownerUserIds) {
        this.ownerUserIds = ownerUserIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ownerUserIds);
    }

    public URI getAccessBaseUri() {
        return accessBaseUri;
    }

    public void setAccessBaseUri(URI accessBaseUri) {
        this.accessBaseUri = accessBaseUri;
    }

    public URI getRuntimeBaseUri() {
        return runtimeBaseUri;
    }

    public void setRuntimeBaseUri(URI runtimeBaseUri) {
        this.runtimeBaseUri = runtimeBaseUri;
    }

    public String getCallerApplicationRef() {
        return callerApplicationRef;
    }

    public void setCallerApplicationRef(String callerApplicationRef) {
        this.callerApplicationRef = callerApplicationRef;
    }

    public String getAccessBearerToken() {
        return accessBearerToken;
    }

    public void setAccessBearerToken(String accessBearerToken) {
        this.accessBearerToken = accessBearerToken;
    }

    public String getRuntimeBearerToken() {
        return runtimeBearerToken;
    }

    public void setRuntimeBearerToken(String runtimeBearerToken) {
        this.runtimeBearerToken = runtimeBearerToken;
    }

    public String getInternalPrincipalPrefix() {
        return internalPrincipalPrefix;
    }

    public void setInternalPrincipalPrefix(String internalPrincipalPrefix) {
        this.internalPrincipalPrefix = internalPrincipalPrefix;
    }

    public EnvironmentClass getEnvironmentClass() {
        return environmentClass;
    }

    public void setEnvironmentClass(EnvironmentClass environmentClass) {
        this.environmentClass = environmentClass;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

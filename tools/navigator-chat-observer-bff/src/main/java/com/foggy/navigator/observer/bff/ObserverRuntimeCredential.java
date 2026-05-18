package com.foggy.navigator.observer.bff;

import java.time.Instant;
import java.util.List;

public class ObserverRuntimeCredential {

    private final String authMode;
    private final String source;
    private final String navigatorBaseUrl;
    private final String clientAppId;
    private final String clientAppName;
    private final String clientAppKey;
    private final String clientAppSecret;
    private final String clientAppAccessToken;
    private final String upstreamUserId;
    private final String agentId;
    private final String modelConfigId;
    private final String authUsername;
    private final String authUserId;
    private final String tenantId;
    private final Instant issuedAt;
    private final List<String> grantSteps;

    public ObserverRuntimeCredential(
            String authMode,
            String source,
            String navigatorBaseUrl,
            String clientAppId,
            String clientAppName,
            String clientAppKey,
            String clientAppSecret,
            String clientAppAccessToken,
            String upstreamUserId,
            String agentId,
            String modelConfigId,
            String authUsername,
            String authUserId,
            String tenantId,
            Instant issuedAt,
            List<String> grantSteps) {
        this.authMode = authMode;
        this.source = source;
        this.navigatorBaseUrl = navigatorBaseUrl;
        this.clientAppId = clientAppId;
        this.clientAppName = clientAppName;
        this.clientAppKey = clientAppKey;
        this.clientAppSecret = clientAppSecret;
        this.clientAppAccessToken = clientAppAccessToken;
        this.upstreamUserId = upstreamUserId;
        this.agentId = agentId;
        this.modelConfigId = modelConfigId;
        this.authUsername = authUsername;
        this.authUserId = authUserId;
        this.tenantId = tenantId;
        this.issuedAt = issuedAt == null ? Instant.now() : issuedAt;
        this.grantSteps = grantSteps == null ? List.of() : List.copyOf(grantSteps);
    }

    public static ObserverRuntimeCredential fromProperties(ObserverBffProperties properties) {
        return new ObserverRuntimeCredential(
                "client-app-runtime",
                "env",
                properties.navigatorBaseUrl(),
                properties.clientAppId(),
                null,
                properties.clientAppKey(),
                properties.clientAppSecret(),
                properties.clientAppAccessToken(),
                properties.upstreamUserId(),
                properties.agentId(),
                properties.modelConfigId(),
                null,
                null,
                null,
                Instant.now(),
                List.of("env-runtime"));
    }

    public boolean hasRuntime() {
        return !ObserverBffProperties.isBlank(clientAppKey)
                && (!ObserverBffProperties.isBlank(clientAppSecret)
                || !ObserverBffProperties.isBlank(clientAppAccessToken));
    }

    public String authMode() {
        return authMode;
    }

    public String source() {
        return source;
    }

    public String navigatorBaseUrl() {
        return navigatorBaseUrl;
    }

    public String clientAppId() {
        return clientAppId;
    }

    public String clientAppName() {
        return clientAppName;
    }

    public String clientAppKey() {
        return clientAppKey;
    }

    public String clientAppSecret() {
        return clientAppSecret;
    }

    public String clientAppAccessToken() {
        return clientAppAccessToken;
    }

    public String upstreamUserId() {
        return upstreamUserId;
    }

    public String agentId() {
        return agentId;
    }

    public String modelConfigId() {
        return modelConfigId;
    }

    public String authUsername() {
        return authUsername;
    }

    public String authUserId() {
        return authUserId;
    }

    public String tenantId() {
        return tenantId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public List<String> grantSteps() {
        return grantSteps;
    }
}

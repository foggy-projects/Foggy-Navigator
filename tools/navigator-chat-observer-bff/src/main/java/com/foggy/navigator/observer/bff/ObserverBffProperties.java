package com.foggy.navigator.observer.bff;

import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;

public class ObserverBffProperties {

    private final String navigatorBaseUrl;
    private final String clientAppKey;
    private final String clientAppSecret;
    private final String clientAppAccessToken;
    private final String clientAppId;
    private final String upstreamUserId;
    private final String apiKey;
    private final String bearerToken;
    private final String agentId;
    private final String modelConfigId;
    private final String debugClientAppName;
    private final String debugCapabilityDomain;
    private final String publicBaseUrl;
    private final Path attachmentStorageDir;
    private final Duration sdkTimeout;

    private ObserverBffProperties(
            String navigatorBaseUrl,
            String clientAppKey,
            String clientAppSecret,
            String clientAppAccessToken,
            String clientAppId,
            String upstreamUserId,
            String apiKey,
            String bearerToken,
            String agentId,
            String modelConfigId,
            String debugClientAppName,
            String debugCapabilityDomain,
            String publicBaseUrl,
            Path attachmentStorageDir,
            Duration sdkTimeout) {
        this.navigatorBaseUrl = trimTrailingSlash(navigatorBaseUrl);
        this.clientAppKey = clean(clientAppKey);
        this.clientAppSecret = clean(clientAppSecret);
        this.clientAppAccessToken = clean(clientAppAccessToken);
        this.clientAppId = clean(clientAppId);
        this.upstreamUserId = clean(upstreamUserId);
        this.apiKey = clean(apiKey);
        this.bearerToken = clean(bearerToken);
        this.agentId = clean(agentId);
        this.modelConfigId = clean(modelConfigId);
        this.debugClientAppName = defaultIfBlank(debugClientAppName, "Navigator Chat Observer Debug BFF").trim();
        this.debugCapabilityDomain = defaultIfBlank(debugCapabilityDomain, "navigator-chat-observer").trim();
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.attachmentStorageDir = attachmentStorageDir;
        this.sdkTimeout = sdkTimeout;
    }

    public static ObserverBffProperties from(Environment env) {
        String port = first(env, "server.port", "NAVIGATOR_OBSERVER_BFF_PORT");
        if (isBlank(port)) {
            port = "5181";
        }
        String publicBaseUrl = first(env,
                "navigator.observer.public-base-url",
                "NAVIGATOR_OBSERVER_PUBLIC_BASE_URL");
        if (isBlank(publicBaseUrl)) {
            publicBaseUrl = "http://127.0.0.1:" + port;
        }

        String storageDir = first(env,
                "navigator.observer.attachment-storage-dir",
                "NAVIGATOR_OBSERVER_ATTACHMENT_STORAGE_DIR");
        if (isBlank(storageDir)) {
            storageDir = ".foggy/observer-bff/attachments";
        }

        long timeoutSeconds = parseLong(first(env,
                "navigator.observer.sdk-timeout-seconds",
                "NAVIGATOR_OBSERVER_SDK_TIMEOUT_SECONDS"), 60);

        return new ObserverBffProperties(
                defaultIfBlank(first(env, "navigator.observer.navigator-base-url", "NAVIGATOR_BASE_URL", "NAVI_BASE_URL"),
                        "http://127.0.0.1:8112"),
                first(env, "navigator.observer.client-app-key", "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY"),
                first(env, "navigator.observer.client-app-secret", "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET"),
                first(env, "navigator.observer.client-app-access-token", "NAVI_CLIENT_APP_ACCESS_TOKEN", "CLIENT_APP_ACCESS_TOKEN"),
                first(env, "navigator.observer.client-app-id", "NAVIGATOR_OBSERVER_CLIENT_APP_ID", "NAVI_CLIENT_APP_ID", "CLIENT_APP_ID"),
                defaultIfBlank(first(env, "navigator.observer.upstream-user-id", "NAVI_UPSTREAM_USER_ID", "UPSTREAM_USER_ID"),
                        "observer-local-user"),
                first(env, "navigator.observer.api-key", "NAVI_API_KEY", "NAVIGATOR_API_KEY"),
                first(env, "navigator.observer.bearer-token", "NAVI_BEARER_TOKEN", "NAVIGATOR_BEARER_TOKEN"),
                defaultIfBlank(first(env, "navigator.observer.agent-id", "NAVI_AGENT_ID", "NAVIGATOR_AGENT_ID"),
                        "observer-agent"),
                first(env, "navigator.observer.model-config-id", "NAVI_MODEL_CONFIG_ID", "NAVIGATOR_MODEL_CONFIG_ID"),
                first(env, "navigator.observer.debug-client-app-name", "NAVIGATOR_OBSERVER_CLIENT_APP_NAME"),
                first(env, "navigator.observer.debug-capability-domain", "NAVIGATOR_OBSERVER_CAPABILITY_DOMAIN"),
                publicBaseUrl,
                Path.of(storageDir).toAbsolutePath().normalize(),
                Duration.ofSeconds(timeoutSeconds));
    }

    public String navigatorBaseUrl() {
        return navigatorBaseUrl;
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

    public String clientAppId() {
        return clientAppId;
    }

    public String upstreamUserId() {
        return upstreamUserId;
    }

    public String apiKey() {
        return apiKey;
    }

    public String bearerToken() {
        return bearerToken;
    }

    public String agentId() {
        return agentId;
    }

    public String modelConfigId() {
        return modelConfigId;
    }

    public String debugClientAppName() {
        return debugClientAppName;
    }

    public String debugCapabilityDomain() {
        return debugCapabilityDomain;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    public Path attachmentStorageDir() {
        return attachmentStorageDir;
    }

    public Duration sdkTimeout() {
        return sdkTimeout;
    }

    public boolean hasClientAppRuntime() {
        return !isBlank(clientAppKey) && (!isBlank(clientAppSecret) || !isBlank(clientAppAccessToken));
    }

    public String authMode() {
        if (hasClientAppRuntime()) {
            return "client-app-runtime";
        }
        if (!isBlank(apiKey)) {
            return "api-key";
        }
        if (!isBlank(bearerToken)) {
            return "bearer";
        }
        return "missing";
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String first(Environment env, String... names) {
        for (String name : names) {
            String value = env.getProperty(name);
            if (!isBlank(value)) {
                return value;
            }
            value = System.getenv(name);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String clean(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static long parseLong(String value, long fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

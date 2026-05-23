package com.foggy.navigator.observer.bff;

import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.AgentTask;
import com.foggy.navigator.sdk.model.SessionListPage;
import com.foggy.navigator.sdk.model.SessionMessagesPage;
import com.foggy.navigator.sdk.model.TaskMessagesPage;
import com.foggy.navigator.sdk.model.businessagent.ClientAppRuntimeAccessTokenDTO;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class NavigatorObserverService {

    private final ObserverBffProperties properties;
    private final NavigatorAccountLoginBootstrapService loginBootstrapService;
    private final NavigatorClient defaultClient;
    private final NavigatorClient runtimeClient;

    private volatile ObserverRuntimeCredential loginRuntimeCredential;
    private volatile String cachedAccessToken;
    private volatile String cachedAccessTokenKey;
    private volatile Instant refreshTokenAt = Instant.EPOCH;

    public NavigatorObserverService(
            ObserverBffProperties properties,
            NavigatorAccountLoginBootstrapService loginBootstrapService) {
        this.properties = properties;
        this.loginBootstrapService = loginBootstrapService;
        this.defaultClient = buildDefaultClient(properties);
        this.runtimeClient = buildRuntimeClient(properties);
    }

    public Map<String, Object> observerConfig() {
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("authMode", authMode(runtime));
        config.put("authSource", runtime == null ? null : runtime.source());
        config.put("navigatorBaseUrl", runtime == null ? properties.navigatorBaseUrl() : runtime.navigatorBaseUrl());
        config.put("bffBaseUrl", properties.publicBaseUrl());
        config.put("agentId", defaultAgentId(runtime));
        config.put("upstreamUserId", defaultUpstreamUserId(runtime));
        config.put("modelConfigId", defaultModelConfigId(runtime));
        config.put("clientAppId", runtime == null ? properties.clientAppId() : runtime.clientAppId());
        config.put("clientAppName", runtime == null ? null : runtime.clientAppName());
        config.put("authUsername", runtime == null ? null : runtime.authUsername());
        config.put("authUserId", runtime == null ? null : runtime.authUserId());
        config.put("tenantId", runtime == null ? null : runtime.tenantId());
        config.put("grantSteps", runtime == null ? List.of() : runtime.grantSteps());
        config.put("issuedAt", runtime == null ? null : runtime.issuedAt().toString());
        config.put("attachmentUploadUrl", properties.publicBaseUrl() + "/api/v1/observer/attachments");
        config.put("attachmentStorageDir", properties.attachmentStorageDir().toString());
        return config;
    }

    public Map<String, Object> loginWithNavigatorAccount(Map<String, Object> body) {
        loginRuntimeCredential = loginBootstrapService.loginAndBootstrap(body);
        clearRuntimeTokenCache();
        return observerConfig();
    }

    public AgentTask ask(String agentId, Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        requireNavigatorAuth();
        String question = firstText(request, "question", "message");
        if (ObserverBffProperties.isBlank(question)) {
            throw new ResponseStatusException(BAD_REQUEST, "question or message is required");
        }
        String contextId = text(request.get("contextId"));
        Integer maxTurns = integer(request.get("maxTurns"));
        Map<String, Object> clientContext = map(request.get("clientContext"));
        String modelConfigId = resolveModelConfigId(request);
        List<Map<String, Object>> attachments = listOfMaps(request.get("attachments"));

        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().askWithClientAppAccessToken(
                    agentId,
                    question,
                    contextId,
                    maxTurns,
                    clientContext,
                    modelConfigId,
                    attachments,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    resolveUpstreamUserId(request));
        }

        return defaultClient.agents().askWithAttachments(
                agentId,
                question,
                contextId,
                maxTurns,
                clientContext,
                modelConfigId,
                attachments);
    }

    public AgentTask getTask(String agentId, String taskId) {
        requireNavigatorAuth();
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().getTaskWithClientAppAccessToken(
                    agentId,
                    taskId,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        }
        return defaultClient.agents().getTask(agentId, taskId);
    }

    public TaskMessagesPage getTaskMessages(String agentId, String taskId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 50);
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().getTaskMessagesWithClientAppAccessToken(
                    agentId,
                    taskId,
                    resolvedLimit,
                    cursor,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        }
        return defaultClient.agents().getTaskMessages(agentId, taskId, resolvedLimit, cursor);
    }

    public Map<String, Object> cancelTask(String agentId, String taskId) {
        requireNavigatorAuth();
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            runtimeClient(runtime).agents().cancelTaskWithClientAppAccessToken(
                    agentId,
                    taskId,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        } else {
            defaultClient.agents().cancelTask(agentId, taskId);
        }
        return Map.of("cancelled", true);
    }

    public List<AgentTask> listTasks(String agentId) {
        requireNavigatorAuth();
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().listTasksWithClientAppAccessToken(
                    agentId,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        }
        return defaultClient.agents().listTasks(agentId);
    }

    public SessionListPage listSessions(String agentId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 20);
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().listBusinessAgentSessionsWithClientAppAccessToken(
                    resolvedLimit,
                    cursor,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        }
        return defaultClient.agents().listSessions(agentId, resolvedLimit, cursor);
    }

    public SessionMessagesPage getSessionMessages(String agentId, String contextId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 50);
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().getBusinessAgentSessionMessagesWithClientAppAccessToken(
                    contextId,
                    resolvedLimit,
                    cursor,
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime),
                    defaultUpstreamUserId(runtime));
        }
        return defaultClient.agents().getSessionMessages(agentId, contextId, resolvedLimit, cursor);
    }

    public Object preflight(String agentId, Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        requireNavigatorAuth();
        ObserverRuntimeCredential runtime = activeClientAppRuntime();
        if (runtime != null) {
            return runtimeClient(runtime).agents().verifyReadinessWithClientAppAccessToken(
                    agentId,
                    resolveUpstreamUserId(request),
                    resolveModelConfigId(request),
                    runtime.clientAppKey(),
                    runtimeAccessToken(runtime));
        }
        return Map.of(
                "ready", true,
                "authMode", authMode(runtime),
                "agent", defaultClient.agents().get(agentId));
    }

    private NavigatorClient buildDefaultClient(ObserverBffProperties props) {
        NavigatorClient.Builder builder = NavigatorClient.builder()
                .baseUrl(props.navigatorBaseUrl())
                .timeout(props.sdkTimeout());

        if (!ObserverBffProperties.isBlank(props.apiKey())) {
            return builder.apiKey(props.apiKey()).build();
        }
        if (!ObserverBffProperties.isBlank(props.bearerToken())) {
            return builder.bearerToken(props.bearerToken()).build();
        }
        return builder.noDefaultAuth().build();
    }

    private NavigatorClient buildRuntimeClient(ObserverBffProperties props) {
        return NavigatorClient.builder()
                .baseUrl(props.navigatorBaseUrl())
                .timeout(props.sdkTimeout())
                .noDefaultAuth()
                .build();
    }

    private NavigatorClient runtimeClient(ObserverRuntimeCredential runtime) {
        if (runtime == null
                || ObserverBffProperties.isBlank(runtime.navigatorBaseUrl())
                || properties.navigatorBaseUrl().equals(runtime.navigatorBaseUrl())) {
            return runtimeClient;
        }
        return NavigatorClient.builder()
                .baseUrl(runtime.navigatorBaseUrl())
                .timeout(properties.sdkTimeout())
                .noDefaultAuth()
                .build();
    }

    private synchronized String runtimeAccessToken(ObserverRuntimeCredential runtime) {
        if (!ObserverBffProperties.isBlank(runtime.clientAppAccessToken())) {
            return runtime.clientAppAccessToken();
        }
        if (ObserverBffProperties.isBlank(runtime.clientAppSecret())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "NAVI_CLIENT_APP_SECRET or NAVI_CLIENT_APP_ACCESS_TOKEN is required");
        }
        Instant now = Instant.now();
        String tokenKey = runtime.authMode() + ":" + runtime.navigatorBaseUrl() + ":" + runtime.clientAppKey();
        if (!ObserverBffProperties.isBlank(cachedAccessToken)
                && tokenKey.equals(cachedAccessTokenKey)
                && now.isBefore(refreshTokenAt)) {
            return cachedAccessToken;
        }

        ClientAppRuntimeAccessTokenDTO token = runtimeClient(runtime).businessAgent()
                .exchangeRuntimeAccessToken(runtime.clientAppKey(), runtime.clientAppSecret());
        if (token == null || ObserverBffProperties.isBlank(token.getAccessToken())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Navigator returned empty runtime access token");
        }

        long ttlSeconds = token.getExpiresInSeconds() == null ? 300 : token.getExpiresInSeconds();
        long refreshSeconds = ttlSeconds > 120 ? ttlSeconds - 60 : Math.max(1, ttlSeconds / 2);
        cachedAccessToken = token.getAccessToken();
        cachedAccessTokenKey = tokenKey;
        refreshTokenAt = now.plusSeconds(refreshSeconds);
        return cachedAccessToken;
    }

    private void requireNavigatorAuth() {
        if (activeClientAppRuntime() != null
                || !ObserverBffProperties.isBlank(properties.apiKey())
                || !ObserverBffProperties.isBlank(properties.bearerToken())) {
            return;
        }
        throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                "Configure NAVI_CLIENT_APP_KEY with NAVI_CLIENT_APP_SECRET or NAVI_CLIENT_APP_ACCESS_TOKEN, configure NAVI_API_KEY/NAVI_BEARER_TOKEN, or authorize from the observer page with a Navi account");
    }

    private ObserverRuntimeCredential activeClientAppRuntime() {
        if (properties.hasClientAppRuntime()) {
            return ObserverRuntimeCredential.fromProperties(properties);
        }
        ObserverRuntimeCredential runtime = loginRuntimeCredential;
        return runtime != null && runtime.hasRuntime() ? runtime : null;
    }

    private String authMode(ObserverRuntimeCredential runtime) {
        if (runtime != null) {
            return runtime.authMode();
        }
        if (!ObserverBffProperties.isBlank(properties.apiKey())) {
            return "api-key";
        }
        if (!ObserverBffProperties.isBlank(properties.bearerToken())) {
            return "bearer";
        }
        return "missing";
    }

    private String defaultAgentId(ObserverRuntimeCredential runtime) {
        if (runtime != null && !ObserverBffProperties.isBlank(runtime.agentId())) {
            return runtime.agentId();
        }
        return properties.agentId();
    }

    private String defaultUpstreamUserId(ObserverRuntimeCredential runtime) {
        if (runtime != null && !ObserverBffProperties.isBlank(runtime.upstreamUserId())) {
            return runtime.upstreamUserId();
        }
        return properties.upstreamUserId();
    }

    private String defaultModelConfigId(ObserverRuntimeCredential runtime) {
        if (runtime != null && !ObserverBffProperties.isBlank(runtime.modelConfigId())) {
            return runtime.modelConfigId();
        }
        return properties.modelConfigId();
    }

    private String resolveModelConfigId(Map<String, Object> body) {
        String modelConfigId = text(body.get("modelConfigId"));
        if (!ObserverBffProperties.isBlank(modelConfigId)) {
            return modelConfigId;
        }
        Map<String, Object> metadata = map(body.get("metadata"));
        modelConfigId = text(metadata.get("modelConfigId"));
        if (!ObserverBffProperties.isBlank(modelConfigId)) {
            return modelConfigId;
        }
        return defaultModelConfigId(activeClientAppRuntime());
    }

    private String resolveUpstreamUserId(Map<String, Object> body) {
        String upstreamUserId = text(body.get("upstreamUserId"));
        return ObserverBffProperties.isBlank(upstreamUserId)
                ? defaultUpstreamUserId(activeClientAppRuntime())
                : upstreamUserId;
    }

    private void clearRuntimeTokenCache() {
        cachedAccessToken = null;
        cachedAccessTokenKey = null;
        refreshTokenAt = Instant.EPOCH;
    }

    private String firstText(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            String value = text(body.get(key));
            if (!ObserverBffProperties.isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> source)) {
            return result;
        }
        source.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?> items)) {
            return result;
        }
        for (Object item : items) {
            Map<String, Object> mapped = map(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        }
        return result;
    }

    private int normalizeLimit(Integer limit, int fallback) {
        if (limit == null) {
            return fallback;
        }
        return Math.max(1, Math.min(limit, 200));
    }
}

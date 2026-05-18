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
    private final NavigatorClient client;

    private volatile String cachedAccessToken;
    private volatile Instant refreshTokenAt = Instant.EPOCH;

    public NavigatorObserverService(ObserverBffProperties properties) {
        this.properties = properties;
        this.client = buildClient(properties);
    }

    public Map<String, Object> observerConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("authMode", properties.authMode());
        config.put("navigatorBaseUrl", properties.navigatorBaseUrl());
        config.put("bffBaseUrl", properties.publicBaseUrl());
        config.put("agentId", properties.agentId());
        config.put("upstreamUserId", properties.upstreamUserId());
        config.put("modelConfigId", properties.modelConfigId());
        config.put("attachmentUploadUrl", properties.publicBaseUrl() + "/api/v1/observer/attachments");
        config.put("attachmentStorageDir", properties.attachmentStorageDir().toString());
        return config;
    }

    public AgentTask ask(String agentId, Map<String, Object> body) {
        requireNavigatorAuth();
        String question = firstText(body, "question", "message");
        if (ObserverBffProperties.isBlank(question)) {
            throw new ResponseStatusException(BAD_REQUEST, "question or message is required");
        }
        String contextId = text(body.get("contextId"));
        Integer maxTurns = integer(body.get("maxTurns"));
        Map<String, Object> clientContext = map(body.get("clientContext"));
        String modelConfigId = resolveModelConfigId(body);
        List<Map<String, Object>> attachments = listOfMaps(body.get("attachments"));

        if (properties.hasClientAppRuntime()) {
            return client.agents().askWithClientAppAccessToken(
                    agentId,
                    question,
                    contextId,
                    maxTurns,
                    clientContext,
                    modelConfigId,
                    attachments,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    resolveUpstreamUserId(body));
        }

        return client.agents().askWithAttachments(
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
        if (properties.hasClientAppRuntime()) {
            return client.agents().getTaskWithClientAppAccessToken(
                    agentId,
                    taskId,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        }
        return client.agents().getTask(agentId, taskId);
    }

    public TaskMessagesPage getTaskMessages(String agentId, String taskId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 50);
        if (properties.hasClientAppRuntime()) {
            return client.agents().getTaskMessagesWithClientAppAccessToken(
                    agentId,
                    taskId,
                    resolvedLimit,
                    cursor,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        }
        return client.agents().getTaskMessages(agentId, taskId, resolvedLimit, cursor);
    }

    public Map<String, Object> cancelTask(String agentId, String taskId) {
        requireNavigatorAuth();
        if (properties.hasClientAppRuntime()) {
            client.agents().cancelTaskWithClientAppAccessToken(
                    agentId,
                    taskId,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        } else {
            client.agents().cancelTask(agentId, taskId);
        }
        return Map.of("cancelled", true);
    }

    public List<AgentTask> listTasks(String agentId) {
        requireNavigatorAuth();
        if (properties.hasClientAppRuntime()) {
            return client.agents().listTasksWithClientAppAccessToken(
                    agentId,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        }
        return client.agents().listTasks(agentId);
    }

    public SessionListPage listSessions(String agentId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 20);
        if (properties.hasClientAppRuntime()) {
            return client.agents().listSessionsWithClientAppAccessToken(
                    agentId,
                    resolvedLimit,
                    cursor,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        }
        return client.agents().listSessions(agentId, resolvedLimit, cursor);
    }

    public SessionMessagesPage getSessionMessages(String agentId, String contextId, Integer limit, String cursor) {
        requireNavigatorAuth();
        int resolvedLimit = normalizeLimit(limit, 50);
        if (properties.hasClientAppRuntime()) {
            return client.agents().getSessionMessagesWithClientAppAccessToken(
                    agentId,
                    contextId,
                    resolvedLimit,
                    cursor,
                    properties.clientAppKey(),
                    runtimeAccessToken(),
                    properties.upstreamUserId());
        }
        return client.agents().getSessionMessages(agentId, contextId, resolvedLimit, cursor);
    }

    public Object preflight(String agentId, Map<String, Object> body) {
        requireNavigatorAuth();
        if (properties.hasClientAppRuntime()) {
            return client.agents().verifyReadinessWithClientAppAccessToken(
                    agentId,
                    resolveUpstreamUserId(body),
                    resolveModelConfigId(body),
                    properties.clientAppKey(),
                    runtimeAccessToken());
        }
        return Map.of(
                "ready", true,
                "authMode", properties.authMode(),
                "agent", client.agents().get(agentId));
    }

    private NavigatorClient buildClient(ObserverBffProperties props) {
        NavigatorClient.Builder builder = NavigatorClient.builder()
                .baseUrl(props.navigatorBaseUrl())
                .timeout(props.sdkTimeout());

        if (props.hasClientAppRuntime()) {
            return builder.noDefaultAuth().build();
        }
        if (!ObserverBffProperties.isBlank(props.apiKey())) {
            return builder.apiKey(props.apiKey()).build();
        }
        if (!ObserverBffProperties.isBlank(props.bearerToken())) {
            return builder.bearerToken(props.bearerToken()).build();
        }
        return builder.noDefaultAuth().build();
    }

    private synchronized String runtimeAccessToken() {
        if (!ObserverBffProperties.isBlank(properties.clientAppAccessToken())) {
            return properties.clientAppAccessToken();
        }
        if (ObserverBffProperties.isBlank(properties.clientAppSecret())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "NAVI_CLIENT_APP_SECRET or NAVI_CLIENT_APP_ACCESS_TOKEN is required");
        }
        Instant now = Instant.now();
        if (!ObserverBffProperties.isBlank(cachedAccessToken) && now.isBefore(refreshTokenAt)) {
            return cachedAccessToken;
        }

        ClientAppRuntimeAccessTokenDTO token = client.businessAgent()
                .exchangeRuntimeAccessToken(properties.clientAppKey(), properties.clientAppSecret());
        if (token == null || ObserverBffProperties.isBlank(token.getAccessToken())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Navigator returned empty runtime access token");
        }

        long ttlSeconds = token.getExpiresInSeconds() == null ? 300 : token.getExpiresInSeconds();
        long refreshSeconds = ttlSeconds > 120 ? ttlSeconds - 60 : Math.max(1, ttlSeconds / 2);
        cachedAccessToken = token.getAccessToken();
        refreshTokenAt = now.plusSeconds(refreshSeconds);
        return cachedAccessToken;
    }

    private void requireNavigatorAuth() {
        if (properties.hasClientAppRuntime()
                || !ObserverBffProperties.isBlank(properties.apiKey())
                || !ObserverBffProperties.isBlank(properties.bearerToken())) {
            return;
        }
        throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                "Configure NAVI_CLIENT_APP_KEY with NAVI_CLIENT_APP_SECRET or NAVI_CLIENT_APP_ACCESS_TOKEN, or configure NAVI_API_KEY/NAVI_BEARER_TOKEN");
    }

    private String resolveModelConfigId(Map<String, Object> body) {
        String modelConfigId = text(body.get("modelConfigId"));
        if (!ObserverBffProperties.isBlank(modelConfigId)) {
            return modelConfigId;
        }
        Map<String, Object> metadata = map(body.get("metadata"));
        modelConfigId = text(metadata.get("modelConfigId"));
        return ObserverBffProperties.isBlank(modelConfigId) ? properties.modelConfigId() : modelConfigId;
    }

    private String resolveUpstreamUserId(Map<String, Object> body) {
        String upstreamUserId = text(body.get("upstreamUserId"));
        return ObserverBffProperties.isBlank(upstreamUserId) ? properties.upstreamUserId() : upstreamUserId;
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

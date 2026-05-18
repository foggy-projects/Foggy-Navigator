package com.foggy.navigator.sdk.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal HTTP helper — wraps Java HttpClient + Jackson for Navigator Open API calls.
 * <p>
 * All responses are expected in {@code RX<T>} format: {@code {"code": 0, "data": ..., "msg": "..."}}
 * or the Foggy framework convention {@code {"code": 200, "data": ..., "msg": "..."}}
 */
public class HttpHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpHelper.class);

    private final String baseUrl;
    private final String apiKey;
    private final String bearerToken;
    private final String clientAppControlKey;
    private final String operatorApiKey;
    private final String upstreamAdminApiKey;
    private final String tenantId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public HttpHelper(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, null, null, timeout);
    }

    public HttpHelper(String baseUrl, String apiKey, String bearerToken, String tenantId, Duration timeout) {
        this(baseUrl, apiKey, bearerToken, tenantId, null, timeout);
    }

    public HttpHelper(String baseUrl, String apiKey, String bearerToken, String tenantId,
                      String clientAppControlKey, Duration timeout) {
        this(baseUrl, apiKey, bearerToken, tenantId, clientAppControlKey, null, timeout);
    }

    public HttpHelper(String baseUrl, String apiKey, String bearerToken, String tenantId,
                      String clientAppControlKey, String operatorApiKey, Duration timeout) {
        this(baseUrl, apiKey, bearerToken, tenantId, clientAppControlKey, operatorApiKey, null, timeout);
    }

    public HttpHelper(String baseUrl, String apiKey, String bearerToken, String tenantId,
                      String clientAppControlKey, String operatorApiKey,
                      String upstreamAdminApiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.bearerToken = bearerToken;
        this.clientAppControlKey = clientAppControlKey;
        this.operatorApiKey = operatorApiKey;
        this.upstreamAdminApiKey = upstreamAdminApiKey;
        this.tenantId = tenantId;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(javaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ===== HTTP methods =====

    public <T> T get(String path, TypeReference<T> type) {
        return execute(buildRequest("GET", path, null), type);
    }

    public <T> T get(String path, Map<String, String> headers, TypeReference<T> type) {
        return execute(buildRequest("GET", path, null, true, headers), type);
    }

    public <T> T post(String path, Object body, TypeReference<T> type) {
        return execute(buildRequest("POST", path, body), type);
    }

    public <T> T post(String path, Object body, Map<String, String> headers, TypeReference<T> type) {
        return execute(buildRequest("POST", path, body, true, headers), type);
    }

    public <T> T getWithUpstreamAdminAuth(String path, String upstreamAdminApiKeyOverride, TypeReference<T> type) {
        return execute(buildRequest("GET", path, null, false,
                upstreamAdminOnlyHeaders(upstreamAdminApiKeyOverride)), type);
    }

    public <T> T postWithUpstreamAdminAuth(String path, Object body, String upstreamAdminApiKeyOverride,
                                           TypeReference<T> type) {
        return execute(buildRequest("POST", path, body, false,
                upstreamAdminOnlyHeaders(upstreamAdminApiKeyOverride)), type);
    }

    public <T> T put(String path, Object body, TypeReference<T> type) {
        return execute(buildRequest("PUT", path, body), type);
    }

    public <T> T put(String path, Object body, Map<String, String> headers, TypeReference<T> type) {
        return execute(buildRequest("PUT", path, body, true, headers), type);
    }

    public void delete(String path) {
        execute(buildRequest("DELETE", path, null), new TypeReference<Void>() {});
    }

    /**
     * POST without auth (e.g. /register)
     */
    public <T> T postNoAuth(String path, Object body, TypeReference<T> type) {
        return execute(buildRequest("POST", path, body, false), type);
    }

    // ===== Internals =====

    private HttpRequest buildRequest(String method, String path, Object body) {
        return buildRequest(method, path, body, true);
    }

    private HttpRequest buildRequest(String method, String path, Object body, boolean withAuth) {
        return buildRequest(method, path, body, withAuth, null);
    }

    private HttpRequest buildRequest(
            String method,
            String path,
            Object body,
            boolean withAuth,
            Map<String, String> headers) {
        String url = baseUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (withAuth) {
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.header("Authorization", normalizeBearerToken(bearerToken));
            }
            if (clientAppControlKey != null && !clientAppControlKey.isBlank()) {
                builder.header("X-Client-App-Control-Key", clientAppControlKey);
            }
            if (operatorApiKey != null && !operatorApiKey.isBlank()) {
                builder.header("X-Navi-Operator-Key", operatorApiKey);
            }
            if (upstreamAdminApiKey != null && !upstreamAdminApiKey.isBlank()) {
                builder.header("X-Navi-Admin-Key", upstreamAdminApiKey);
            }
            if (tenantId != null && !tenantId.isBlank()) {
                builder.header("X-Tenant-Id", tenantId);
            }
        }

        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                    builder.header(name, value);
                }
            });
        }

        if (body != null) {
            try {
                String json = objectMapper.writeValueAsString(body);
                builder.method(method, HttpRequest.BodyPublishers.ofString(json));
            } catch (Exception e) {
                throw new NavigatorApiException("Failed to serialize request body", e);
            }
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private String normalizeBearerToken(String token) {
        String trimmed = token.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return trimmed;
        }
        return "Bearer " + trimmed;
    }

    private Map<String, String> upstreamAdminOnlyHeaders(String override) {
        Map<String, String> headers = new LinkedHashMap<>();
        String adminKey = override != null && !override.isBlank() ? override : upstreamAdminApiKey;
        if (adminKey != null && !adminKey.isBlank()) {
            headers.put("X-Navi-Admin-Key", adminKey);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            headers.put("X-Tenant-Id", tenantId);
        }
        return headers;
    }

    private static JavaTimeModule javaTimeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addDeserializer(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
            @Override
            public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String value = parser.getValueAsString();
                if (value == null || value.isBlank()) {
                    return null;
                }
                try {
                    return LocalDateTime.parse(value);
                } catch (DateTimeParseException ignored) {
                    return OffsetDateTime.parse(value).toLocalDateTime();
                }
            }
        });
        return module;
    }

    private <T> T execute(HttpRequest request, TypeReference<T> type) {
        try {
            log.debug("SDK {} {}", request.method(), request.uri());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new NavigatorApiException(response.statusCode(), response.body());
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            if (root.isObject() && root.has("code")) {
                int code = root.get("code").asInt(-1);
                if (code != 0 && code != 200) {
                    String msg = root.has("msg") ? root.get("msg").asText() : body;
                    throw new NavigatorApiException(response.statusCode(),
                            "API error (code=" + code + "): " + msg);
                }

                JsonNode dataNode = root.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    return null;
                }
                return objectMapper.convertValue(dataNode, type);
            } else {
                // Raw JSON response
                return objectMapper.convertValue(root, type);
            }
        } catch (NavigatorApiException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NavigatorApiException("HTTP request failed: " + request.uri(), e);
        }
    }
}

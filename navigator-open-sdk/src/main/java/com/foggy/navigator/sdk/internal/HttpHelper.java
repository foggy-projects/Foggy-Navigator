package com.foggy.navigator.sdk.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Internal HTTP helper — wraps Java HttpClient + Jackson for Navigator Open API calls.
 * <p>
 * All responses are expected in {@code RX<T>} format: {@code {"code": 0, "data": ..., "msg": "..."}}
 */
public class HttpHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpHelper.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public HttpHelper(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ===== HTTP methods =====

    public <T> T get(String path, TypeReference<T> type) {
        return execute(buildRequest("GET", path, null), type);
    }

    public <T> T post(String path, Object body, TypeReference<T> type) {
        return execute(buildRequest("POST", path, body), type);
    }

    public <T> T put(String path, Object body, TypeReference<T> type) {
        return execute(buildRequest("PUT", path, body), type);
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
        String url = baseUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (withAuth && apiKey != null) {
            builder.header("X-API-Key", apiKey);
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
                if (code != 0) {
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

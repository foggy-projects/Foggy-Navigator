package com.foggy.navigator.langgraph.worker.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HTTP client for calling Navigator Java Worker Gateway internal APIs.
 * All calls require a task-scoped token in the X-Task-Scoped-Token header.
 * This client enforces fail-closed: missing token or HTTP errors throw exceptions.
 */
@Slf4j
public class WorkerGatewayClient {

    private static final String HEADER_TASK_SCOPED_TOKEN = "X-Task-Scoped-Token";
    private final WebClient webClient;

    public WorkerGatewayClient(String gatewayBaseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(gatewayBaseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    /**
     * Package-private constructor for testing with a pre-built WebClient.
     */
    WorkerGatewayClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * List business functions visible to the current task/session/skill scope.
     */
    public Map<String, Object> listBusinessFunctions(String token, String domain, String riskLevel) {
        requireToken(token);

        WebClient.RequestHeadersSpec<?> spec = webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/internal/worker-gateway/v1/business-functions");
                    if (StringUtils.hasText(domain)) uriBuilder.queryParam("domain", domain);
                    if (StringUtils.hasText(riskLevel)) uriBuilder.queryParam("riskLevel", riskLevel);
                    return uriBuilder.build();
                })
                .header(HEADER_TASK_SCOPED_TOKEN, token);

        return executeBlocking(spec.retrieve().bodyToMono(mapType()), "listBusinessFunctions");
    }

    /**
     * Get the schema of a specific business function.
     */
    public Map<String, Object> getBusinessFunctionSchema(String token, String functionId, String version) {
        requireToken(token);

        WebClient.RequestHeadersSpec<?> spec = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/worker-gateway/v1/business-functions/{functionId}/schema")
                        .queryParam("version", version)
                        .build(functionId))
                .header(HEADER_TASK_SCOPED_TOKEN, token);

        return executeBlocking(spec.retrieve().bodyToMono(mapType()), "getBusinessFunctionSchema");
    }

    /**
     * Invoke a business function. Returns the invoke response including status and optional suspendId.
     */
    public Map<String, Object> invokeBusinessFunction(String token, String functionId, Map<String, Object> body) {
        requireToken(token);

        WebClient.RequestBodySpec spec = webClient.post()
                .uri("/internal/worker-gateway/v1/business-functions/{functionId}/invoke", functionId)
                .header(HEADER_TASK_SCOPED_TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON);

        return executeBlocking(spec.bodyValue(body).retrieve().bodyToMono(mapType()), "invokeBusinessFunction");
    }

    /**
     * Report a tool execution message (audit event) to Java Gateway.
     */
    public Map<String, Object> reportToolMessage(String token, Map<String, Object> body) {
        requireToken(token);

        WebClient.RequestBodySpec spec = webClient.post()
                .uri("/internal/worker-gateway/v1/tool-messages")
                .header(HEADER_TASK_SCOPED_TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON);

        return executeBlocking(spec.bodyValue(body).retrieve().bodyToMono(mapType()), "reportToolMessage");
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("task_scoped_token is required for Worker Gateway calls");
        }
    }

    private Map<String, Object> executeBlocking(Mono<Map<String, Object>> mono, String operation) {
        try {
            return mono.block();
        } catch (WebClientResponseException e) {
            log.error("Worker Gateway {} failed: HTTP {} - {}", operation, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Worker Gateway " + operation + " failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Worker Gateway {} failed unexpectedly", operation, e);
            throw new RuntimeException("Worker Gateway " + operation + " failed", e);
        }
    }

    private static ParameterizedTypeReference<Map<String, Object>> mapType() {
        return new ParameterizedTypeReference<>() {};
    }
}

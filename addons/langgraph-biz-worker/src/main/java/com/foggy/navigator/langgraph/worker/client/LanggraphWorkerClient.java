package com.foggy.navigator.langgraph.worker.client;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the LangGraph Biz Worker Python service.
 */
@Slf4j
public class LanggraphWorkerClient {

    private final WebClient webClient;
    private final String workerId;

    public LanggraphWorkerClient(String workerId, String baseUrl, String authToken) {
        this(workerId, baseUrl, authToken, Duration.ofSeconds(10), Duration.ofMinutes(30));
    }

    public LanggraphWorkerClient(
            String workerId,
            String baseUrl,
            String authToken,
            Duration connectTimeout,
            Duration responseTimeout
    ) {
        this.workerId = workerId;
        long rawConnectTimeoutMillis = connectTimeout == null ? 10_000 : connectTimeout.toMillis();
        int connectTimeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, rawConnectTimeoutMillis));
        long rawResponseTimeoutMillis = responseTimeout == null ? Duration.ofMinutes(30).toMillis() : responseTimeout.toMillis();
        Duration effectiveResponseTimeout = Duration.ofMillis(Math.max(1, rawResponseTimeoutMillis));
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(effectiveResponseTimeout);
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * Health check.
     */
    public Mono<Map<String, Object>> healthCheck() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.warn("Health check failed for langgraph worker {}: {}", workerId, e.getMessage()));
    }

    /**
     * Stream a query to the Python Worker and return the SSE event flux.
     */
    public Flux<ServerSentEvent<String>> streamQuery(
            String prompt,
            Map<String, Object> context,
            Map<String, Object> runtimeContext,
            String model,
            String modelConfigId,
            Map<String, Object> llmConfig,
            Map<String, Object> visionLlmConfig,
            String taskId,
            String sessionId,
            String userId,
            String tenantId,
            Integer maxTurns,
            List<Map<String, Object>> attachments
    ) {
        return streamQuery(
                prompt,
                null,
                context,
                runtimeContext,
                model,
                modelConfigId,
                llmConfig,
                visionLlmConfig,
                taskId,
                sessionId,
                userId,
                tenantId,
                maxTurns,
                attachments
        );
    }

    /**
     * Stream a query to the Python Worker and return the SSE event flux.
     */
    public Flux<ServerSentEvent<String>> streamQuery(
            String prompt,
            String skillName,
            Map<String, Object> context,
            Map<String, Object> runtimeContext,
            String model,
            String modelConfigId,
            Map<String, Object> llmConfig,
            Map<String, Object> visionLlmConfig,
            String taskId,
            String sessionId,
            String userId,
            String tenantId,
            Integer maxTurns,
            List<Map<String, Object>> attachments
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);
        if (skillName != null && !skillName.isBlank()) body.put("skill_name", skillName.trim());
        if (context != null) body.put("context", context);
        if (runtimeContext != null) body.put("runtime_context", runtimeContext);
        if (model != null) body.put("model", model);
        if (modelConfigId != null) body.put("model_config_id", modelConfigId);
        if (llmConfig != null && !llmConfig.isEmpty()) body.put("llm_config", llmConfig);
        if (visionLlmConfig != null && !visionLlmConfig.isEmpty()) body.put("vision_llm_config", visionLlmConfig);
        if (taskId != null) body.put("taskId", taskId);
        if (sessionId != null) body.put("session_id", sessionId);
        if (userId != null) body.put("userId", userId);
        if (tenantId != null) body.put("tenantId", tenantId);
        if (maxTurns != null && maxTurns > 0) body.put("max_turns", maxTurns);
        if (attachments != null && !attachments.isEmpty()) body.put("attachments", attachments);

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * Resume a suspended task on the Python Worker.
     * contextId is required by the Worker to locate the session-sharded frame.
     */
    @Deprecated(forRemoval = false)
    public Mono<Map<String, Object>> resumeTask(String taskId, String approvalResult, String comment) {
        return Mono.error(new IllegalArgumentException("contextId is required for LangGraph worker resume"));
    }

    public Mono<Map<String, Object>> resumeTask(
            String taskId,
            String sessionId,
            String contextId,
            String approvalResult,
            String comment
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        if (sessionId != null) body.put("sessionId", sessionId);
        if (contextId != null) body.put("contextId", contextId);
        body.put("approvalResult", approvalResult);
        if (comment != null) body.put("comment", comment);

        return webClient.post()
                .uri("/api/v1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.warn("Resume failed for task {}: {}", taskId, e.getMessage()));
    }

    /**
     * Record a recoverable interruption on the Worker's persistent root frame.
     */
    public Mono<Map<String, Object>> recordInterruption(
            String taskId,
            String sessionId,
            String contextId,
            String reason,
            String error,
            Map<String, Object> context
    ) {
        Map<String, Object> body = new HashMap<>();
        if (taskId != null) body.put("taskId", taskId);
        if (sessionId != null) body.put("session_id", sessionId);
        if (contextId != null) body.put("context_id", contextId);
        body.put("reason", reason);
        if (error != null) body.put("error", error);
        if (context != null && !context.isEmpty()) body.put("context", context);

        return webClient.post()
                .uri("/api/v1/frames/interruption")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.warn("Record interruption failed for task {}: {}", taskId, e.getMessage()));
    }

    /**
     * Reconcile a Java-owned business function terminal result back into
     * Python Worker frame execution reports.
     */
    public Mono<Map<String, Object>> recordBusinessFunctionResult(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/v1/frames/business-function-result")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.warn("Business function report reconciliation failed for task {}: {}",
                        body != null ? body.get("taskId") : null, e.getMessage()));
    }
}

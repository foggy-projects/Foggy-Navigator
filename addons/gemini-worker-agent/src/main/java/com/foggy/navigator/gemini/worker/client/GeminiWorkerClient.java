package com.foggy.navigator.gemini.worker.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini Worker HTTP 客户端
 */
@Slf4j
public class GeminiWorkerClient {

    private final WebClient webClient;

    public GeminiWorkerClient(String baseUrl, String authToken) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(30));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));
        if (authToken != null && !authToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + authToken);
        }
        this.webClient = builder.build();
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> healthCheck() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Gemini health check failed: {}", e.getMessage());
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("status", "ERROR");
                    error.put("error", e.getMessage());
                    return Mono.just(error);
                });
    }

    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd,
                                                     String geminiSessionId, String model,
                                                     Integer maxTurns, String apiKey,
                                                     String baseUrl, Map<String, String> envVars,
                                                     List<Map<String, Object>> attachments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        if (cwd != null) body.put("cwd", cwd);
        if (geminiSessionId != null) body.put("session_id", geminiSessionId);
        if (model != null) body.put("model", model);
        if (maxTurns != null) body.put("max_turns", maxTurns);
        if (apiKey != null) body.put("api_key", apiKey);
        if (baseUrl != null) body.put("base_url", baseUrl);
        if (envVars != null && !envVars.isEmpty()) body.put("env_vars", envVars);
        if (attachments != null && !attachments.isEmpty()) body.put("attachments", attachments);

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    public Flux<ServerSentEvent<String>> subscribeToTask(String taskId, int ackSeq) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/tasks/{taskId}/subscribe")
                        .queryParam("ack_seq", ackSeq)
                        .build(taskId))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getTaskStatus(String taskId) {
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/status", taskId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<Void> abortTask(String taskId) {
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/abort", taskId)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> listSessions() {
        return webClient.get()
                .uri("/api/v1/sessions")
                .retrieve()
                .bodyToMono(List.class)
                .map(list -> (List<Map<String, Object>>) list)
                .timeout(Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> listCliProcesses() {
        return webClient.get()
                .uri("/api/v1/processes")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> killCliProcess(int pid, boolean force) {
        return webClient.post()
                .uri("/api/v1/processes/{pid}/kill", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("force", force))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }
}

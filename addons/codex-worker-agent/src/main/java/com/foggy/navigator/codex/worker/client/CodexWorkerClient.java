package com.foggy.navigator.codex.worker.client;

import lombok.extern.slf4j.Slf4j;
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
 * Codex Worker HTTP 客户端
 * 精简版，仅需 6 个方法（对比 ClaudeWorkerClient 的 20+）
 */
@Slf4j
public class CodexWorkerClient {

    private final WebClient webClient;

    public CodexWorkerClient(String baseUrl, String authToken) {
        // 自定义 Netty HttpClient：30 分钟连接超时（SSE 长连接）
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(30));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));

        if (authToken != null && !authToken.isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + authToken);
        }

        this.webClient = builder.build();
    }

    /**
     * 健康检查
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> healthCheck() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Health check failed: {}", e.getMessage());
                    Map<String, Object> errorResult = new LinkedHashMap<>();
                    errorResult.put("status", "ERROR");
                    errorResult.put("error", e.getMessage());
                    return Mono.just(errorResult);
                });
    }

    /**
     * 流式查询 — 返回 SSE 流
     *
     * @param prompt        用户提示
     * @param cwd           工作目录
     * @param codexThreadId Codex SDK thread ID（null 表示新会话）
     * @param model         模型名称
     * @param maxTurns      最大轮次
     * @param apiKey        OpenAI API Key（可选，覆盖 Worker 默认）
     * @param baseUrl       OpenAI Base URL（可选，覆盖 Worker 默认）
     * @param envVars       额外环境变量（可选，含 Codex CLI config 如 model_context_window）
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd,
                                                      String codexThreadId, String model,
                                                      Integer maxTurns, String images,
                                                      List<Map<String, Object>> attachments,
                                                      String apiKey, String baseUrl,
                                                      java.util.Map<String, String> envVars) {
        return streamQuery(prompt, cwd, codexThreadId, model, maxTurns, images, attachments,
                apiKey, baseUrl, envVars,
                null, null, null, null, null, null, null, null, null);
    }

    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd,
                                                      String codexThreadId, String model,
                                                      Integer maxTurns, String images,
                                                      List<Map<String, Object>> attachments,
                                                      String apiKey, String baseUrl,
                                                      java.util.Map<String, String> envVars,
                                                      String codexHomeKey,
                                                      String developerInstructions,
                                                      Map<String, Object> outputSchema,
                                                      Map<String, Object> codexConfig,
                                                      String sandboxMode,
                                                      String approvalPolicy,
                                                      Boolean networkAccessEnabled,
                                                      String webSearchMode,
                                                      List<String> additionalDirectories) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        if (cwd != null) body.put("cwd", cwd);
        if (codexThreadId != null) body.put("session_id", codexThreadId);
        if (model != null) body.put("model", model);
        if (maxTurns != null) body.put("max_turns", maxTurns);
        if (images != null && !images.isBlank()) {
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(images, List.class);
                body.put("images", parsed);
            } catch (Exception e) {
                log.warn("Failed to parse images JSON, skipping: {}", e.getMessage());
            }
        }
        if (attachments != null && !attachments.isEmpty()) {
            body.put("attachments", attachments);
        }
        if (apiKey != null) body.put("api_key", apiKey);
        if (baseUrl != null) body.put("base_url", baseUrl);
        if (envVars != null && !envVars.isEmpty()) body.put("env_vars", envVars);
        if (codexHomeKey != null && !codexHomeKey.isBlank()) body.put("codex_home_key", codexHomeKey);
        if (developerInstructions != null && !developerInstructions.isBlank()) {
            body.put("developer_instructions", developerInstructions);
        }
        if (outputSchema != null && !outputSchema.isEmpty()) body.put("output_schema", outputSchema);
        if (codexConfig != null && !codexConfig.isEmpty()) body.put("codex_config", codexConfig);
        if (sandboxMode != null && !sandboxMode.isBlank()) body.put("sandbox_mode", sandboxMode);
        if (approvalPolicy != null && !approvalPolicy.isBlank()) body.put("approval_policy", approvalPolicy);
        if (networkAccessEnabled != null) body.put("network_access_enabled", networkAccessEnabled);
        if (webSearchMode != null && !webSearchMode.isBlank()) body.put("web_search_mode", webSearchMode);
        if (additionalDirectories != null && !additionalDirectories.isEmpty()) {
            body.put("additional_directories", additionalDirectories);
        }

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * 订阅已有任务的 SSE 流（用于断线重连）
     *
     * @param taskId 任务 ID
     * @param ackSeq 已确认的最新事件序列号
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> subscribeToTask(String taskId, int ackSeq) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/tasks/{taskId}/subscribe")
                        .queryParam("ack_seq", ackSeq)
                        .build(taskId))
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * 获取任务状态
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getTaskStatus(String taskId) {
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/status", taskId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 中止任务
     */
    public Mono<Void> abortTask(String taskId) {
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/abort", taskId)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 列出 Worker 上的会话
     */
    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> listSessions() {
        return webClient.get()
                .uri("/api/v1/sessions")
                .retrieve()
                .bodyToMono(List.class)
                .map(list -> (List<Map<String, Object>>) list)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 查询 Worker 本地记录的 Codex session 文件改动线索。
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getSessionFileHints(String sessionId, Integer days, String from, String to) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/api/v1/session-file-hints")
                            .queryParam("session_id", sessionId);
                    if (days != null) {
                        builder.queryParam("days", days);
                    }
                    if (from != null && !from.isBlank()) {
                        builder.queryParam("from", from);
                    }
                    if (to != null && !to.isBlank()) {
                        builder.queryParam("to", to);
                    }
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 列出 Worker 上的 Codex CLI 进程
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> listCliProcesses() {
        return webClient.get()
                .uri("/api/v1/processes")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 终止 Worker 上的 Codex CLI 进程
     */
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

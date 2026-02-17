package com.foggy.navigator.claude.worker.client;

import com.foggy.navigator.claude.worker.model.event.WorkerEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Worker HTTP/SSE 客户端
 * 用于与远程 Agent Worker 通信
 */
@Slf4j
public class ClaudeWorkerClient {

    private final WebClient webClient;
    private final String workerId;

    public ClaudeWorkerClient(String workerId, String baseUrl, String authToken) {
        this.workerId = workerId;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + authToken)
                .build();
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
                .doOnError(e -> log.warn("Health check failed for worker {}: {}", workerId, e.getMessage()));
    }

    /**
     * 流式查询 - 返回 Worker SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd, String sessionId,
                                                       String model, Integer maxTurns,
                                                       String agentTeamsJson) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("prompt", prompt);
        if (cwd != null) {
            body.put("cwd", cwd);
        }
        if (sessionId != null) {
            body.put("session_id", sessionId);
        }
        if (model != null) {
            body.put("model", model);
        }
        if (maxTurns != null) {
            body.put("max_turns", maxTurns);
        }
        if (agentTeamsJson != null && !agentTeamsJson.isEmpty()) {
            Map<String, Object> extraArgs = new java.util.HashMap<>();
            extraArgs.put("agents", agentTeamsJson);
            body.put("extra_args", extraArgs);
        }

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    /**
     * 中止任务
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> abortTask(String taskId) {
        return webClient.post()
                .uri("/api/v1/query/{taskId}/abort", taskId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Abort task failed for worker {}, task {}: {}", workerId, taskId, e.getMessage()));
    }

    /**
     * 列出 Worker 上的 Claude Code 会话
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> listSessions() {
        return webClient.get()
                .uri("/api/v1/sessions")
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("List sessions failed for worker {}: {}", workerId, e.getMessage()));
    }

    /**
     * 查询 Git 仓库信息
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getGitInfo(String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-info").queryParam("path", path).build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Git info failed for worker {}, path {}: {}", workerId, path, e.getMessage()));
    }

    /**
     * 获取项目技能列表
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> listSkills(String cwd) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/skills").queryParam("cwd", cwd).build())
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("List skills failed for worker {}, cwd {}: {}", workerId, cwd, e.getMessage()));
    }

    public String getWorkerId() {
        return workerId;
    }
}

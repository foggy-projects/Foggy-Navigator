package com.foggy.navigator.claude.worker.client;

import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker HTTP/SSE 客户端
 * 用于与远程 Agent Worker 通信
 */
@Slf4j
public class ClaudeWorkerClient {

    private final WebClient webClient;
    private final String workerId;
    /** Never log or serialize; only used to sign one-shot termination capabilities. */
    private final String terminationSigningSecret;

    public ClaudeWorkerClient(String workerId, String baseUrl, String authToken) {
        this.workerId = workerId;
        this.terminationSigningSecret = authToken;
        // Configure Netty HttpClient with generous timeouts for long-running SSE streams.
        // responseTimeout=30min prevents WebClient from closing idle SSE connections
        // (especially during AWAITING_PERMISSION where no events flow for extended periods).
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofMinutes(30));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(24 * 1024 * 1024)); // cover tool outputs and file preview payloads
        if (authToken != null && !authToken.isBlank()) {
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
                .doOnError(e -> log.warn("Health check failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 流式查询 - 返回 Worker SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd, String sessionId,
                                                       String model, Integer maxTurns,
                                                       String agentTeamsJson) {
        return streamQuery(prompt, cwd, sessionId, model, maxTurns, agentTeamsJson, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 流式查询（含 per-request auth 覆盖、图片附件和权限模式）
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd, String sessionId,
                                                       String model, Integer maxTurns,
                                                       String agentTeamsJson, String images,
                                                       String apiKey, String authToken, String baseUrl,
                                                       String permissionMode,
                                                       String navigatorApiKey,
                                                       String foggyTaskId, String foggySessionId) {
        return streamQuery(prompt, cwd, sessionId, model, maxTurns, agentTeamsJson, images,
                null, apiKey, authToken, baseUrl, permissionMode, navigatorApiKey, null, foggyTaskId, foggySessionId, null);
    }

    /**
     * 流式查询（含 per-request auth 覆盖、图片附件、权限模式和额外环境变量）
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd, String sessionId,
                                                       String model, Integer maxTurns,
                                                       String agentTeamsJson, String images,
                                                       String apiKey, String authToken, String baseUrl,
                                                       String permissionMode,
                                                       String navigatorApiKey,
                                                       String foggyTaskId, String foggySessionId,
                                                       Map<String, String> extraEnvVars) {
        return streamQuery(prompt, cwd, sessionId, model, maxTurns, agentTeamsJson, images,
                null, apiKey, authToken, baseUrl, permissionMode, navigatorApiKey, null, foggyTaskId, foggySessionId, extraEnvVars);
    }

    /**
     * 流式查询（含 per-request auth 覆盖、图片附件、权限模式、Navigator 平台 URL 和额外环境变量）
     */
    public Flux<ServerSentEvent<String>> streamQuery(String prompt, String cwd, String sessionId,
                                                       String model, Integer maxTurns,
                                                       String agentTeamsJson, String images,
                                                       List<Map<String, Object>> attachments,
                                                       String apiKey, String authToken, String baseUrl,
                                                       String permissionMode,
                                                       String navigatorApiKey,
                                                       String navigatorApiBase,
                                                       String foggyTaskId, String foggySessionId,
                                                       Map<String, String> extraEnvVars) {
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
        // Image attachments (JSON array string → parsed list)
        if (images != null && !images.isEmpty()) {
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(images, java.util.List.class);
                body.put("images", parsed);
            } catch (Exception e) {
                log.warn("Failed to parse images JSON, skipping: errorType={}", exceptionType(e));
            }
        }
        if (attachments != null && !attachments.isEmpty()) {
            body.put("attachments", attachments);
        }
        // Per-request auth overrides
        if (apiKey != null) {
            body.put("api_key", apiKey);
        }
        if (authToken != null) {
            body.put("auth_token", authToken);
        }
        if (baseUrl != null) {
            body.put("base_url", baseUrl);
        }
        // Permission mode
        if (permissionMode != null && !permissionMode.isEmpty()) {
            body.put("permission_mode", permissionMode);
        }
        // Navigator platform API Key (for CLI env injection)
        if (navigatorApiKey != null && !navigatorApiKey.isEmpty()) {
            body.put("navigator_api_key", navigatorApiKey);
        }
        // Navigator platform base URL (for CLI env injection as NAVIGATOR_API_BASE)
        if (navigatorApiBase != null && !navigatorApiBase.isEmpty()) {
            body.put("navigator_api_base", navigatorApiBase);
        }
        // Foggy platform tracking IDs (injected as env vars into CLI subprocess)
        if (foggyTaskId != null && !foggyTaskId.isEmpty()) {
            body.put("foggy_task_id", foggyTaskId);
        }
        if (foggySessionId != null && !foggySessionId.isEmpty()) {
            body.put("foggy_session_id", foggySessionId);
        }
        // Extra environment variables from LLM model config
        if (extraEnvVars != null && !extraEnvVars.isEmpty()) {
            body.put("extra_env_vars", extraEnvVars);
        }

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnError(e -> log.warn("Stream query failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 响应权限请求
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> respondToPermission(String taskId, String permissionId,
                                                          String decision, String denyMessage,
                                                          String scope,
                                                          java.util.Map<String, String> answers,
                                                          String planAction) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("permission_id", permissionId);
        body.put("decision", decision);
        if (denyMessage != null) {
            body.put("deny_message", denyMessage);
        }
        if (scope != null) {
            body.put("scope", scope);
        }
        if (answers != null && !answers.isEmpty()) {
            body.put("answers", answers);
        }
        if (planAction != null) {
            body.put("plan_action", planAction);
        }
        return webClient.post()
                .uri("/api/v1/query/{taskId}/respond", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Respond to permission failed for worker {}, task {}: errorCode={}, errorType={}",
                        workerId, taskId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 获取 Worker 当前 auth 配置
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getAuthConfig() {
        return webClient.get()
                .uri("/api/v1/auth-config")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Get auth config failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 回退文件到指定 checkpoint
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> rewindFiles(String claudeSessionId, String checkpointId, String cwd) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("claude_session_id", claudeSessionId);
        body.put("checkpoint_id", checkpointId);
        if (cwd != null) {
            body.put("cwd", cwd);
        }
        return webClient.post()
                .uri("/api/v1/query/rewind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Rewind files failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 中止任务
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> abortTask(String taskId, TerminationOperationCapability capability) {
        requireCapability(capability);
        return webClient.post()
                .uri("/api/v1/query/{taskId}/abort", taskId)
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Abort task failed for worker {}, task {}: errorCode={}, errorType={}",
                        workerId, taskId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * Force-cancel an owned task through the Worker's task-bound process
     * resolver. The caller never supplies a PID.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> forceAbortTask(
            String taskId, TerminationOperationCapability capability) {
        requireCapability(capability);
        return webClient.post()
                .uri("/api/v1/query/{taskId}/force-abort", taskId)
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn(
                        "Force abort task failed for worker {}, task {}: errorCode={}, errorType={}",
                        workerId, taskId, remoteErrorCode(e), exceptionType(e)));
    }

    /** @deprecated Remote termination without an audited capability is rejected. */
    @Deprecated
    public Mono<Map<String, Object>> abortTask(String taskId) {
        return Mono.error(new IllegalStateException("TERMINATION_CAPABILITY_REQUIRED"));
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
                .doOnError(e -> log.warn("List sessions failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 扫描会话 JSONL 提取 checkpoint（UserMessage UUID）
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> scanSessionCheckpoints(String sessionId) {
        return webClient.get()
                .uri("/api/v1/sessions/{sessionId}/checkpoints", sessionId)
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("Scan session checkpoints failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 统计会话 JSONL 中的消息数量
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getSessionMessageCount(String sessionId) {
        return webClient.get()
                .uri("/api/v1/sessions/{sessionId}/message-count", sessionId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Get session message count failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 读取 Claude Code 本地会话的对话历史（全量）
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> getSessionMessages(String sessionId) {
        return webClient.get()
                .uri("/api/v1/sessions/{sessionId}/messages", sessionId)
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("Get session messages failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 读取 Claude Code 本地会话的对话历史（分页）
     *
     * @param sessionId 会话ID
     * @param offset    跳过的消息数
     * @param limit     返回的最大消息数（null 表示不限制）
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> getSessionMessages(String sessionId, int offset, Integer limit) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/v1/sessions/{sessionId}/messages")
                            .queryParam("offset", offset);
                    if (limit != null) {
                        b = b.queryParam("limit", limit);
                    }
                    return b.build(sessionId);
                })
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("Get session messages (paginated) failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 回退会话到指定轮次（标记后续消息为 sidechain）
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> rewindConversation(String sessionId, int turnIndex) {
        return webClient.post()
                .uri("/api/v1/sessions/{sessionId}/rewind-conversation", sessionId)
                .bodyValue(Map.of("turnIndex", turnIndex))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Rewind conversation failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 触发 Worker 重新扫描 Claude Code 本地会话
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> syncSessions() {
        return webClient.post()
                .uri("/api/v1/sessions/sync")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Sync sessions failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
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
                .doOnError(e -> log.warn("Git info failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
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
                .doOnError(e -> log.warn("List skills failed for worker {}, cwd {}: errorCode={}, errorType={}",
                        workerId, cwd, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 列出工作树
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> listWorktrees(String repoPath) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/worktrees").queryParam("path", repoPath).build())
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("List worktrees failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, repoPath, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 创建工作树
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> createWorktree(String repoPath, String branch, String worktreePath) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("repo_path", repoPath);
        body.put("branch", branch);
        if (worktreePath != null) {
            body.put("worktree_path", worktreePath);
        }
        return webClient.post()
                .uri("/api/v1/worktrees")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Create worktree failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, repoPath, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 删除工作树
     */
    public Mono<Void> removeWorktree(String worktreePath) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("worktree_path", worktreePath);
        return webClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/v1/worktrees")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnError(e -> log.warn("Remove worktree failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, worktreePath, remoteErrorCode(e), exceptionType(e)));
    }

    // ---- File browser --------------------------------------------------------

    /**
     * 列出目录内容
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> listFiles(String path, boolean showHidden) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/files")
                        .queryParam("path", path)
                        .queryParam("show_hidden", showHidden)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("List files failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 读取文件内容
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> readFileContent(String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/files/content")
                        .queryParam("path", path)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Read file content failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 读取文件原始字节
     */
    public Mono<ResponseEntity<byte[]>> readRawFile(String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/files/raw")
                        .queryParam("path", path)
                        .build())
                .retrieve()
                .toEntity(byte[].class)
                .doOnError(e -> log.warn("Read raw file failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 获取 Git diff 摘要
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getGitDiffSummary(String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-diff")
                        .queryParam("path", path)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Git diff summary failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 搜索文件名
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> searchFiles(String path, String query, int maxResults) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/files/search")
                        .queryParam("path", path)
                        .queryParam("query", query)
                        .queryParam("max_results", maxResults)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Search files failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 搜索文件内容
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> searchContent(String path, String query, int maxResults,
                                                     int contextLines, boolean caseSensitive,
                                                     String filePattern) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/v1/files/search-content")
                            .queryParam("path", path)
                            .queryParam("query", query)
                            .queryParam("max_results", maxResults)
                            .queryParam("context_lines", contextLines)
                            .queryParam("case_sensitive", caseSensitive);
                    if (filePattern != null && !filePattern.isBlank()) {
                        b = b.queryParam("file_pattern", filePattern);
                    }
                    return b.build();
                })
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Search content failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 获取单文件 diff (HEAD vs 工作区)
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getFileDiff(String path, String file) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-diff/file")
                        .queryParam("path", path)
                        .queryParam("file", file)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("File diff failed for worker {}, path {}, file {}: errorCode={}, errorType={}",
                        workerId, path, file, remoteErrorCode(e), exceptionType(e)));
    }

    // ===== Git Log / History Methods =====

    /**
     * 获取 git log（分页 + 分支 ahead/behind）
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getGitLog(String path, int limit, int skip) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-log")
                        .queryParam("path", path)
                        .queryParam("limit", limit)
                        .queryParam("skip", skip)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Git log failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 获取 commit 详情（文件列表 + 统计）
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getCommitDetail(String path, String hash) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-log/commit")
                        .queryParam("path", path)
                        .queryParam("hash", hash)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Commit detail failed for worker {}, path {}, hash {}: errorCode={}, errorType={}",
                        workerId, path, hash, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 获取 commit 中单文件的 parent vs commit diff
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getCommitFileDiff(String path, String hash, String file) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/git-log/commit/file-diff")
                        .queryParam("path", path)
                        .queryParam("hash", hash)
                        .queryParam("file", file)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Commit file diff failed for worker {}, path {}, hash {}, file {}: errorCode={}, errorType={}",
                        workerId, path, hash, file, remoteErrorCode(e), exceptionType(e)));
    }

    // ===== Foggy Ignore Methods =====

    /**
     * 获取 .foggy-ignore 中的自定义排除模式
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getFoggyIgnore(String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/files/ignore")
                        .queryParam("path", path)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Get foggy ignore failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 添加一个排除模式到 .foggy-ignore
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> addFoggyIgnore(String path, String pattern) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("pattern", pattern);
        return webClient.post()
                .uri("/api/v1/files/ignore")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Add foggy ignore failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 从 .foggy-ignore 移除一个排除模式
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> removeFoggyIgnore(String path, String pattern) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("pattern", pattern);
        return webClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/v1/files/ignore")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Remove foggy ignore failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    // ===== SSH Proxy Methods =====

    /**
     * 列出 Worker 上的活跃 SSH 会话
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.List<Map<String, Object>>> listSshSessions() {
        return webClient.get()
                .uri("/api/v1/ssh/sessions")
                .retrieve()
                .bodyToMono(java.util.List.class)
                .map(list -> (java.util.List<Map<String, Object>>) list)
                .doOnError(e -> log.warn("List SSH sessions failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 建立 SSH 连接
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> sshConnect(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/v1/ssh/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("SSH connect failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 关闭 SSH 会话
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> sshClose(String sessionId) {
        return webClient.post()
                .uri("/api/v1/ssh/" + sessionId + "/close")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("SSH close failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 调整 SSH 终端尺寸
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> sshResize(String sessionId, Map<String, Integer> body) {
        return webClient.post()
                .uri("/api/v1/ssh/" + sessionId + "/resize")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("SSH resize failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 上传图片到 SSH 目标机工作目录，返回目标机可读的绝对路径。
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> sshUploadImage(String sessionId, Map<String, Object> body) {
        return webClient.post()
                .uri("/api/v1/ssh/" + sessionId + "/images")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("SSH image upload failed for worker {}, session {}: errorCode={}, errorType={}",
                        workerId, sessionId, remoteErrorCode(e), exceptionType(e)));
    }

    // ===== CLI Process Management =====

    /**
     * 列出 Worker 上的 Claude CLI node 进程
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> listCliProcesses() {
        return webClient.get()
                .uri("/api/v1/processes")
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("List CLI processes failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 终止指定 PID 的 Claude CLI 进程
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> killCliProcess(int pid, boolean force,
                                                     TerminationOperationCapability capability) {
        requireCapability(capability);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("force", force);
        return webClient.post()
                .uri("/api/v1/processes/" + pid + "/kill")
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Kill CLI process failed for worker {}, pid {}: errorCode={}, errorType={}",
                        workerId, pid, remoteErrorCode(e), exceptionType(e)));
    }

    /** @deprecated Manual PID termination without an audited capability is rejected. */
    @Deprecated
    public Mono<Map<String, Object>> killCliProcess(int pid, boolean force) {
        return Mono.error(new IllegalStateException("TERMINATION_CAPABILITY_REQUIRED"));
    }

    /** Package-safe access for the control-plane capability issuer; never log this value. */
    public String terminationSigningSecret() {
        return terminationSigningSecret;
    }

    private static void requireCapability(TerminationOperationCapability capability) {
        if (capability == null || capability.encodedOperation() == null || capability.encodedOperation().isBlank()
                || capability.signature() == null || capability.signature().isBlank()) {
            throw new IllegalArgumentException("TERMINATION_CAPABILITY_REQUIRED");
        }
    }

    /**
     * Remote Worker response bodies can contain CLI stderr and credential-
     * adjacent values.  Lifecycle logging records a stable code and exception
     * type only; callers must not render raw transport messages.
     */
    private static String remoteErrorCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException response) {
                return "CLAUDE_WORKER_HTTP_" + response.getStatusCode().value();
            }
            String message = current.getMessage();
            if (message != null && message.matches("[A-Z][A-Z0-9_]{0,127}")) {
                return message;
            }
            current = current.getCause();
        }
        return "CLAUDE_WORKER_TRANSPORT_UNCONFIRMED";
    }

    private static String exceptionType(Throwable error) {
        return error == null ? "UnknownException" : error.getClass().getSimpleName();
    }

    /**
     * 初始化目录 + 写入文件
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> initDirectory(String path, Map<String, String> files) {
        return webClient.post()
                .uri("/api/v1/init-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("path", path, "files", files))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Init directory failed for worker {}, path {}: errorCode={}, errorType={}",
                        workerId, path, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 推送平台 Skill 到 Worker
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> deploySkills(Map<String, String> skills) {
        return webClient.post()
                .uri("/api/v1/platform-skills/deploy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("skills", skills))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.warn("Deploy skills failed for worker {}: errorCode={}, errorType={}",
                        workerId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 订阅已存在任务的 SSE 事件流（用于 SSE 断连或 Java 重启后重连）。
     * 使用 ESN（Event Sequence Number）的 ack_seq 参数精确回放遗漏事件。
     *
     * @param taskId foggyTaskId 或 Worker 内部 task UUID
     * @param ackSeq 最后确认的事件序列号（0 = 全部重放）。
     *               Worker 返回 seq &gt; ackSeq 的所有事件。
     *               同时传 replay_from 参数兼容旧版 Worker。
     */
    public Flux<ServerSentEvent<String>> subscribeToTask(String taskId, int ackSeq) {
        return subscribeToTask(taskId, ackSeq, () -> { });
    }

    /**
     * Automatic-recovery overload. The callback runs exactly once when HTTP
     * response headers arrive, or when the subscription terminates first.
     */
    public Flux<ServerSentEvent<String>> subscribeToTask(
            String taskId, int ackSeq, Runnable connectionSettledCallback) {
        Runnable callback = Objects.requireNonNull(
                connectionSettledCallback, "connectionSettledCallback must not be null");
        AtomicBoolean settled = new AtomicBoolean(false);
        Runnable signalSettled = () -> {
            if (settled.compareAndSet(false, true)) callback.run();
        };
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/tasks/{taskId}/subscribe")
                        .queryParam("ack_seq", ackSeq)
                        .queryParam("replay_from", ackSeq)  // 向后兼容旧 Worker
                        .build(taskId))
                .exchangeToFlux(response -> {
                    signalSettled.run();
                    if (response.statusCode().isError()) {
                        return response.createException().flatMapMany(Flux::error);
                    }
                    return response.bodyToFlux(
                            new ParameterizedTypeReference<ServerSentEvent<String>>() { });
                })
                .doOnError(ignored -> signalSettled.run())
                .doOnCancel(signalSettled)
                .doOnComplete(signalSettled)
                .doFinally(ignored -> signalSettled.run())
                .doOnError(e -> log.warn("Subscribe stream failed for worker {}, task {}: errorCode={}, errorType={}",
                        workerId, taskId, remoteErrorCode(e), exceptionType(e)));
    }

    /**
     * 查询 Worker 侧任务的实时状态（latest_seq、closed、cli_alive 等）。
     * 用于 Reconciler seq gap 检测和 Java 重启后的状态判断。
     *
     * @param taskId foggyTaskId
     * @return 任务状态 Map，如果查询失败返回 empty Mono
     */
    @SuppressWarnings("unchecked")
    public reactor.core.publisher.Mono<Map<String, Object>> getTaskStatus(String taskId) {
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/status", taskId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .doOnError(e -> log.debug("Task status query failed for worker {}, task {}: errorCode={}, errorType={}",
                        workerId, taskId, remoteErrorCode(e), exceptionType(e)));
    }

    public String getWorkerId() {
        return workerId;
    }
}

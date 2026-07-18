package com.foggy.navigator.codex.worker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeRateLimitsDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Codex Worker HTTP 客户端
 * 精简版，仅需 6 个方法（对比 ClaudeWorkerClient 的 20+）
 */
@Slf4j
public class CodexWorkerClient {

    public static final String EXPECTED_INSTANCE_HEADER = "X-Codex-Expected-Instance-Id";
    public static final String ACTUAL_INSTANCE_HEADER = "X-Codex-Instance-Id";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OPERATION_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final Set<String> USER_INPUT_ERROR_CODES = Set.of(
            "INVALID_USER_INPUT_RESPONSE",
            "TASK_NOT_FOUND",
            "USER_INPUT_NOT_PENDING",
            "USER_INPUT_REQUEST_MISMATCH",
            "USER_INPUT_ALREADY_RESPONDED",
            "USER_INPUT_RUNTIME_AFFINITY_LOST");

    private final WebClient webClient;
    private final WebClient generatedImageWebClient;
    /** Never log or serialize; only used to sign one-shot termination capabilities. */
    private final String terminationSigningSecret;

    public CodexWorkerClient(String baseUrl, String authToken) {
        this(baseUrl, authToken, null);
    }

    public CodexWorkerClient(String baseUrl, String authToken, String expectedInstanceId) {
        this.terminationSigningSecret = authToken;
        this.webClient = buildWebClient(baseUrl, authToken, expectedInstanceId, 4 * 1024 * 1024);
        this.generatedImageWebClient = buildWebClient(
                baseUrl, authToken, expectedInstanceId, 32 * 1024 * 1024);
    }

    private WebClient buildWebClient(String baseUrl, String authToken,
                                     String expectedInstanceId, int maxInMemorySize) {
        // 自定义 Netty HttpClient：30 分钟连接超时（SSE 长连接）
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(30));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize));

        if (authToken != null && !authToken.isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + authToken);
        }
        if (expectedInstanceId != null && !expectedInstanceId.isBlank()) {
            String expected = expectedInstanceId.trim();
            builder.defaultHeader(EXPECTED_INSTANCE_HEADER, expected);
            builder.filter((request, next) -> next.exchange(request).flatMap(response -> {
                String actual = response.headers().asHttpHeaders().getFirst(ACTUAL_INSTANCE_HEADER);
                if (actual != null && expected.equals(actual.trim())) {
                    return Mono.just(response);
                }
                String code = actual == null || actual.isBlank()
                        ? "CODEX_RUNTIME_INSTANCE_PROOF_MISSING"
                        : "CODEX_RUNTIME_INSTANCE_PROOF_MISMATCH";
                return response.releaseBody().then(Mono.error(
                        new RuntimeInstanceProofException(code, expected, actual)));
            }));
        }

        return builder.build();
    }

    public static final class RuntimeInstanceProofException extends IllegalStateException {
        private final String code;

        private RuntimeInstanceProofException(String code, String expected, String actual) {
            super(code + ": expected=" + expected + ", actual="
                    + (actual == null || actual.isBlank() ? "<missing>" : actual));
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static final class UserInputResponseException extends IllegalStateException {
        private final int statusCode;
        private final String code;

        private UserInputResponseException(int statusCode, String code) {
            super(code);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getCode() {
            return code;
        }
    }

    public static class WorkerQueryRejectedException extends IllegalStateException {
        private final int statusCode;
        private final String code;

        public WorkerQueryRejectedException(int statusCode, String code) {
            super(code);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getCode() {
            return code;
        }
    }

    public static final class ThreadActiveException extends WorkerQueryRejectedException {
        private final String sessionId;
        private final String activeTaskId;
        private final Integer activePid;
        private final String conflictSource;

        public ThreadActiveException(int statusCode, String code, String sessionId,
                                     String activeTaskId, Integer activePid, String conflictSource) {
            super(statusCode, code);
            this.sessionId = sessionId;
            this.activeTaskId = activeTaskId;
            this.activePid = activePid;
            this.conflictSource = conflictSource;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getActiveTaskId() {
            return activeTaskId;
        }

        public Integer getActivePid() {
            return activePid;
        }

        public String getConflictSource() {
            return conflictSource;
        }
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
                    log.warn("Codex Worker health check failed: type={}", e.getClass().getSimpleName());
                    Map<String, Object> errorResult = new LinkedHashMap<>();
                    errorResult.put("status", "ERROR");
                    errorResult.put("error", "CODEX_WORKER_HEALTH_UNAVAILABLE");
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
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Runtime capability manifest. Legacy SDK Workers may return 404; callers must
     * treat that as a legacy lane rather than fabricating app-server support.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getCapabilities() {
        return probeCapabilities().map(CapabilityProbe::manifest);
    }

    /**
     * Reads the capability body together with the response instance proof. The
     * registry needs this even before it has an instance id to pin.
     */
    @SuppressWarnings("unchecked")
    public Mono<CapabilityProbe> probeCapabilities() {
        return webClient.get()
                .uri("/api/v1/capabilities")
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    String actualInstanceId = response.headers().asHttpHeaders()
                            .getFirst(ACTUAL_INSTANCE_HEADER);
                    return response.bodyToMono(Map.class)
                            .map(manifest -> new CapabilityProbe(
                                    (Map<String, Object>) manifest, actualInstanceId));
                })
                .timeout(Duration.ofSeconds(10));
    }

    public record CapabilityProbe(Map<String, Object> manifest, String actualInstanceId) {}

    /**
     * Reads the sanitized default CODEX_HOME account quota snapshot.
     */
    public Mono<CodexRuntimeRateLimitsDTO> getRuntimeRateLimits(boolean refresh) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/runtime/rate-limits")
                        .queryParam("refresh", refresh)
                        .build())
                .retrieve()
                .bodyToMono(CodexRuntimeRateLimitsDTO.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Idempotently accepts an app-server task. Execution subscription is a separate
     * operation so Navigator can persist the returned worker task id first.
     */
    public Mono<CodexTaskAcceptanceDTO> createTask(String idempotencyKey, Map<String, Object> body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Mono.error(new IllegalArgumentException("idempotencyKey is required"));
        }
        return webClient.post()
                .uri("/api/v1/tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CodexTaskAcceptanceDTO.class)
                .timeout(Duration.ofSeconds(15));
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
                                                       Map<String, Object> businessRuntimeContext,
                                                       List<String> additionalDirectories) {
        Map<String, Object> body = buildTaskRequest(prompt, cwd, codexThreadId, model, maxTurns,
                images, attachments, apiKey, baseUrl, envVars, codexHomeKey,
                developerInstructions, outputSchema, codexConfig, sandboxMode, approvalPolicy,
                networkAccessEnabled, webSearchMode, businessRuntimeContext, additionalDirectories);

        return webClient.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (response.statusCode().value() == 409) {
                        return response.bodyToMono(Map.class)
                                .defaultIfEmpty(Map.of())
                                .flatMapMany(conflict -> {
                                    String code = stringValue(conflict.get("error"),
                                            stringValue(conflict.get("code"), "CODEX_WORKER_TASK_CONFLICT"));
                                    if ("CODEX_THREAD_ACTIVE".equals(code)) {
                                        return Flux.error(new ThreadActiveException(
                                                409,
                                                code,
                                                stringValue(conflict.get("session_id"), null),
                                                stringValue(conflict.get("active_task_id"), null),
                                                integerValue(conflict.get("active_pid")),
                                                stringValue(conflict.get("conflict_source"), null)));
                                    }
                                    return Flux.error(new WorkerQueryRejectedException(409, code));
                                });
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.createException().flatMapMany(Flux::error);
                    }
                    return response.bodyToFlux(
                            new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {});
                });
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    public Map<String, Object> buildTaskRequest(String prompt, String cwd,
                                                 String codexThreadId, String model,
                                                 Integer maxTurns, String images,
                                                 List<Map<String, Object>> attachments,
                                                 String apiKey, String baseUrl,
                                                 Map<String, String> envVars,
                                                 String codexHomeKey,
                                                 String developerInstructions,
                                                 Map<String, Object> outputSchema,
                                                 Map<String, Object> codexConfig,
                                                 String sandboxMode,
                                                 String approvalPolicy,
                                                 Boolean networkAccessEnabled,
                                                 String webSearchMode,
                                                 Map<String, Object> businessRuntimeContext,
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
                throw new IllegalArgumentException(
                        "INVALID_CODEX_IMAGES: images must be a valid JSON array", e);
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
        if (businessRuntimeContext != null && !businessRuntimeContext.isEmpty()) {
            body.put("business_runtime_context", businessRuntimeContext);
        }
        if (additionalDirectories != null && !additionalDirectories.isEmpty()) {
            body.put("additional_directories", additionalDirectories);
        }
        return body;
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
     * Dispatches an explicitly authorized cancellation. A Worker ACK is not a
     * terminal observation and callers must retain the stream/ownership until
     * a provider terminal event or verified process exit arrives.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> abortTask(String taskId, TerminationOperationCapability capability) {
        requireCapability(capability);
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/abort", taskId)
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(body -> (Map<String, Object>) body);
                    }
                    if (response.statusCode().value() == 409) {
                        return response.createException().flatMap(error -> {
                            Map<String, Object> conflict = responseBodyAsMap(error);
                            if (isAbortPendingConflict(taskId, conflict)) {
                                Map<String, Object> acknowledgement = new LinkedHashMap<>(conflict);
                                acknowledgement.putIfAbsent("task_id", taskId);
                                acknowledgement.put("status", "abort_pending");
                                return Mono.just(acknowledgement);
                            }
                            return Mono.error(error);
                        });
                    }
                    return response.createException().flatMap(Mono::error);
                })
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Interrupts only the exact stale native turn bound to an audited terminal
     * App Server task. The capability, expected runtime-instance proof, and
     * task path are all required; this method never targets a process or PID.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> staleTurnCleanup(
            String taskId, TerminationOperationCapability capability) {
        requireTaskId(taskId);
        requireCapability(capability);
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/stale-turn-cleanup", taskId)
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(value -> (Map<String, Object>) value)
                                .defaultIfEmpty(new LinkedHashMap<>());
                    }
                    return workerQueryRejection(response.statusCode().value(), response.bodyToMono(Map.class));
                })
                .timeout(Duration.ofSeconds(30));
    }

    private static Map<String, Object> responseBodyAsMap(WebClientResponseException error) {
        try {
            Object decoded = OBJECT_MAPPER.readValue(error.getResponseBodyAsByteArray(), Object.class);
            if (!(decoded instanceof Map<?, ?> values)) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    result.put(stringKey, value);
                }
            });
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static boolean isAbortPendingConflict(String taskId, Map<String, Object> conflict) {
        String responseTaskId = stringValue(conflict.get("task_id"), null);
        if (responseTaskId != null && !taskId.equals(responseTaskId)) return false;
        return "abort_pending".equalsIgnoreCase(stringValue(conflict.get("status"), ""))
                || "TERMINATION_OPERATION_PENDING".equals(operationConflictCode(conflict));
    }

    private static String operationConflictCode(Map<String, Object> conflict) {
        for (String key : List.of("error_code", "error", "code")) {
            String value = stringValue(conflict.get(key), null);
            if (value != null) return value;
        }
        return "";
    }

    /** @deprecated Remote termination without an audited capability is rejected. */
    @Deprecated
    public Mono<Map<String, Object>> abortTask(String taskId) {
        return Mono.error(new IllegalStateException("TERMINATION_CAPABILITY_REQUIRED"));
    }

    /**
     * Reads one generated image from the exact app-server Worker instance bound to the task.
     */
    public Mono<ResponseEntity<byte[]>> getGeneratedImage(String taskId, String artifactId) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        if (artifactId == null || !artifactId.matches("[a-f0-9]{32}")) {
            return Mono.error(new IllegalArgumentException("artifactId is invalid"));
        }
        return generatedImageWebClient.get()
                .uri("/api/v1/tasks/{taskId}/generated-images/{artifactId}", taskId, artifactId)
                .retrieve()
                .toEntity(byte[].class)
                .timeout(Duration.ofSeconds(30));
    }

    /**
     * Responds to one pending app-server requestUserInput interaction.
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> respondToTask(String taskId, Map<String, Object> body) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        if (body == null || body.isEmpty()) {
            return Mono.error(new IllegalArgumentException("response body is required"));
        }
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/respond", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(value -> (Map<String, Object>) value)
                                .defaultIfEmpty(new LinkedHashMap<>());
                    }
                    int statusCode = response.statusCode().value();
                    return response.bodyToMono(Map.class)
                            .map(value -> (Map<String, Object>) value)
                            .defaultIfEmpty(Map.of())
                            .flatMap(errorBody -> Mono.error(new UserInputResponseException(
                                    statusCode, userInputErrorCode(statusCode, errorBody))));
                })
                .timeout(Duration.ofSeconds(10));
    }

    /** Reads the latest native app-server token usage observed for one task-bound thread. */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getTaskContextUsage(String taskId) {
        requireTaskId(taskId);
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/context-usage", taskId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(value -> (Map<String, Object>) value)
                                .defaultIfEmpty(new LinkedHashMap<>());
                    }
                    return workerQueryRejection(response.statusCode().value(), response.bodyToMono(Map.class));
                })
                .timeout(Duration.ofSeconds(10));
    }

    /** Starts or replays one idempotent whole-thread native compaction operation. */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> compactTaskContext(String taskId, String operationId) {
        requireTaskId(taskId);
        if (operationId == null || !operationId.matches(OPERATION_ID_PATTERN)) {
            return Mono.error(new IllegalArgumentException("operationId is invalid"));
        }
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/compact-context", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operation_id", operationId))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(value -> (Map<String, Object>) value)
                                .defaultIfEmpty(new LinkedHashMap<>());
                    }
                    return workerQueryRejection(response.statusCode().value(), response.bodyToMono(Map.class));
                })
                .timeout(Duration.ofMinutes(5));
    }

    /** Reads one durable compaction operation without starting another compact turn. */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getTaskContextCompactOperation(
            String taskId, String operationId) {
        requireTaskId(taskId);
        if (operationId == null || !operationId.matches(OPERATION_ID_PATTERN)) {
            return Mono.error(new IllegalArgumentException("operationId is invalid"));
        }
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/compact-context/{operationId}", taskId, operationId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class)
                                .map(value -> (Map<String, Object>) value)
                                .defaultIfEmpty(new LinkedHashMap<>());
                    }
                    return workerQueryRejection(response.statusCode().value(), response.bodyToMono(Map.class));
                })
                .timeout(Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> workerQueryRejection(
            int statusCode, Mono<Map> responseBody) {
        return responseBody
                .map(value -> (Map<String, Object>) value)
                .defaultIfEmpty(Map.of())
                .flatMap(body -> Mono.error(new WorkerQueryRejectedException(
                        statusCode, workerQueryErrorCode(statusCode, body))));
    }

    private String workerQueryErrorCode(int statusCode, Map<String, Object> body) {
        for (String key : List.of("error_code", "error", "code")) {
            String value = stringValue(body.get(key), null);
            if (value != null) return value;
        }
        return "CODEX_WORKER_REQUEST_REJECTED_" + statusCode;
    }

    private void requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
    }

    private String userInputErrorCode(int statusCode, Map<String, Object> body) {
        for (String key : List.of("error_code", "error", "code")) {
            Object value = body.get(key);
            if (value != null && USER_INPUT_ERROR_CODES.contains(value.toString())) {
                return value.toString();
            }
        }
        return switch (statusCode) {
            case 400 -> "INVALID_USER_INPUT_RESPONSE";
            case 404 -> "TASK_NOT_FOUND";
            case 409 -> "USER_INPUT_NOT_PENDING";
            default -> "CODEX_USER_INPUT_RESPONSE_REJECTED";
        };
    }

    /**
     * Removes durable payload/event projections for a terminal app-server task.
     * A missing remote task is an idempotent success for Navigator deletion.
     *
     * @return {@code true} when the Worker removed the task, {@code false} for HTTP 404
     */
    public Mono<Boolean> deleteTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        return webClient.delete()
                .uri("/api/v1/tasks/{taskId}", taskId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().thenReturn(true);
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().thenReturn(false);
                    }
                    return response.createException().flatMap(Mono::error);
                })
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
    public Mono<Map<String, Object>> killCliProcess(int pid, boolean force,
                                                     TerminationOperationCapability capability) {
        requireCapability(capability);
        return webClient.post()
                .uri("/api/v1/processes/{pid}/kill", pid)
                .header(TerminationOperationCapability.OPERATION_HEADER, capability.encodedOperation())
                .header(TerminationOperationCapability.SIGNATURE_HEADER, capability.signature())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("force", force))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(Duration.ofSeconds(10));
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
}

package com.foggy.navigator.claude.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ClaudeWorkerFacade SPI 实现
 */
@Slf4j
@Component
public class ClaudeWorkerFacadeImpl implements ClaudeWorkerFacade {

    /** A2A 异步任务的超时时间：30 分钟 */
    private static final long ASYNC_TIMEOUT_SECONDS = 1800;

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final WorkerStreamRelay streamRelay;
    private final WorkingDirectoryRepository directoryRepository;
    private final LlmModelManager llmModelManager;
    private final UserAuthService userAuthService;
    private final ObjectMapper objectMapper;
    private final Executor a2aAsyncExecutor;

    public ClaudeWorkerFacadeImpl(ClaudeWorkerService workerService,
                                   ClaudeTaskService taskService,
                                   WorkerStreamRelay streamRelay,
                                   WorkingDirectoryRepository directoryRepository,
                                   LlmModelManager llmModelManager,
                                   UserAuthService userAuthService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("a2aAsyncExecutor") Executor a2aAsyncExecutor) {
        this.workerService = workerService;
        this.taskService = taskService;
        this.streamRelay = streamRelay;
        this.directoryRepository = directoryRepository;
        this.llmModelManager = llmModelManager;
        this.userAuthService = userAuthService;
        this.objectMapper = objectMapper;
        this.a2aAsyncExecutor = a2aAsyncExecutor;
    }

    @Override
    public List<Map<String, Object>> listWorkers(String userId) {
        return workerService.listWorkers(userId).stream()
                .map(this::workerToMap)
                .toList();
    }

    @Override
    public Map<String, Object> getWorker(String userId, String workerId) {
        return workerToMap(workerService.getWorker(userId, workerId));
    }

    @Override
    public Map<String, Object> createTask(String userId, Map<String, Object> params) {
        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        TaskDTO dto = taskService.createTask(userId, (String) params.get("tenantId"), form);
        return taskToMap(dto);
    }

    @Override
    public Map<String, Object> getTaskStatus(String userId, String taskId) {
        return taskToMap(taskService.getTask(userId, taskId));
    }

    @Override
    public Map<String, Object> abortTask(String userId, String taskId) {
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        // 不再单独调用 streamRelay.abortStream — taskService.abortTask 内部已统一处理
        taskService.abortTask(taskId);
        return Map.of("taskId", taskId, "status", "ABORTED");
    }

    @Override
    public Map<String, Object> resumeSession(String userId, Map<String, Object> params) {
        ResumeTaskForm form = new ResumeTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setClaudeSessionId((String) params.get("claudeSessionId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        TaskDTO dto = taskService.resumeTask(userId, (String) params.get("tenantId"), form);
        return taskToMap(dto);
    }

    @Override
    public List<Map<String, Object>> listWorkerSessions(String userId, String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            return sessions != null ? sessions : List.of();
        } catch (Exception e) {
            log.warn("Failed to list sessions: workerId={}, error={}", workerId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> listTasks(String userId) {
        return taskService.listTasks(userId).stream()
                .map(this::taskToMap)
                .toList();
    }

    @Override
    public Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                          String cwd, String claudeSessionId, int maxTurns,
                                          String model) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        return doSyncQuery(worker, prompt, cwd, claudeSessionId, model, maxTurns, null, null, null, null);
    }

    @Override
    public Map<String, Object> syncQueryTracked(String userId, String workerId, String prompt,
                                                 String cwd, String claudeSessionId, int maxTurns,
                                                 String model, String sessionId, String directoryId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }

        // 1. 创建 claude_tasks 记录（RUNNING）
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, sessionId, prompt, cwd, directoryId, claudeSessionId);

        Map<String, Object> result;
        try {
            // 2. 解析目录绑定的 LLM 配置 auth + envVars
            String[] auth = resolveDirectoryAuth(directoryId, userId);
            Map<String, String> envVars = resolveDirectoryEnvVars(directoryId, userId);

            // 3. 执行 syncQuery（带 auth + envVars）
            result = doSyncQuery(worker, prompt, cwd, claudeSessionId, model, maxTurns,
                    auth[0], auth[1], auth[2], envVars);

            // 4. 持久化 prompt + result 到 Session（使历史会话面板能显示对话内容）
            String resultText = (String) result.get("resultText");
            taskService.persistTrackedSyncMessages(sessionId, prompt, resultText);

            // 5. 完成/失败记录
            String error = (String) result.get("error");
            String workerTaskId = (String) result.get("workerTaskId");
            String newClaudeSessionId = (String) result.get("claudeSessionId");
            if (error != null) {
                taskService.failTask(taskId, workerTaskId, newClaudeSessionId, truncate(error, 500));
            } else {
                taskService.completeTask(taskId, workerTaskId, newClaudeSessionId,
                        resultText,
                        toBigDecimal(result.get("costUsd")),
                        toLong(result.get("inputTokens")),
                        toLong(result.get("outputTokens")),
                        toLong(result.get("durationMs")),
                        toInteger(result.get("numTurns")),
                        (String) result.get("model"));
            }
        } catch (Exception e) {
            log.error("syncQueryTracked failed after task creation: taskId={}, error={}", taskId, e.getMessage(), e);
            taskService.failTask(taskId, null, claudeSessionId, truncate(e.getMessage(), 500));
            // 异常路径也要持久化消息，让前端会话面板能看到用户发了什么、失败原因
            taskService.persistTrackedSyncMessages(sessionId, prompt,
                    "[系统错误] Worker 请求失败: " + truncate(e.getMessage(), 300));
            result = new LinkedHashMap<>();
            result.put("error", e.getMessage());
        }

        result.put("taskId", taskId);
        return result;
    }

    @Override
    public GeminiConfig getGeminiConfig(String workerId) {
        ClaudeWorkerEntity entity = workerService.getWorkerEntity(workerId);
        return workerService.getDecryptedGeminiConfig(entity);
    }

    @Override
    public void bindDirectoryModelConfig(String userId, String directoryId, String modelConfigId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        if (modelConfigId != null && !modelConfigId.isEmpty()) {
            // 校验 Worker 是否有权使用该模型
            llmModelManager.validateModelAccessForWorker(modelConfigId, entity.getWorkerId());
            entity.setDefaultModelConfigId(modelConfigId);
            // 从模型配置推导 authMode（用于 UI 显示 Auth 状态标签）
            LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig != null) {
                String authMode = (modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isEmpty())
                        ? "CUSTOM_ENDPOINT" : "API_KEY";
                entity.setDefaultAuthMode(authMode);
            }
            entity.setDefaultAuthToken(null);
            entity.setDefaultBaseUrl(null);
        } else {
            entity.setDefaultModelConfigId(null);
        }
        directoryRepository.save(entity);
        log.info("Bound model config to directory: directoryId={}, modelConfigId={}", directoryId, modelConfigId);
    }

    @Override
    public String getDirectoryPath(String userId, String directoryId) {
        return directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .map(WorkingDirectoryEntity::getPath)
                .orElse(null);
    }

    @Override
    public CompletableFuture<Map<String, Object>> asyncQuery(
            String userId, String workerId, String prompt, String cwd,
            String claudeSessionId, int maxTurns, String model,
            String directoryId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            // 用户不是 Worker 直接所有者 → 检查是否同一租户（Open API / A2A 跨用户访问）
            if (!isSameTenant(userId, worker.getTenantId())) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Worker not found: " + workerId));
            }
            log.debug("asyncQuery: tenant-level access granted for userId={}, workerId={}", userId, workerId);
        }

        // 同步解析认证（需要 DB，速度快）
        String[] auth = (directoryId != null) ? resolveDirectoryAuth(directoryId, userId) : new String[3];
        Map<String, String> envVars = (directoryId != null) ? resolveDirectoryEnvVars(directoryId, userId) : null;

        // 异步执行（可能耗时 10 秒 ~ 30 分钟）
        return CompletableFuture.supplyAsync(() ->
                        doSyncQuery(worker, prompt, cwd, claudeSessionId, model, maxTurns,
                                auth[0], auth[1], auth[2], envVars, ASYNC_TIMEOUT_SECONDS),
                a2aAsyncExecutor
        );
    }

    /**
     * 内部 syncQuery 实现，支持可选的 per-request auth 覆盖
     */
    private Map<String, Object> doSyncQuery(ClaudeWorkerEntity worker, String prompt, String cwd,
                                             String claudeSessionId, String model, int maxTurns,
                                             String apiKey, String authToken, String baseUrl,
                                             Map<String, String> extraEnvVars) {
        return doSyncQuery(worker, prompt, cwd, claudeSessionId, model, maxTurns,
                apiKey, authToken, baseUrl, extraEnvVars, null);
    }

    /**
     * 内部 syncQuery 实现（带可选超时覆盖）
     *
     * @param overrideTimeoutSeconds 非 null 时覆盖默认超时（用于 A2A 异步长任务）
     */
    private Map<String, Object> doSyncQuery(ClaudeWorkerEntity worker, String prompt, String cwd,
                                             String claudeSessionId, String model, int maxTurns,
                                             String apiKey, String authToken, String baseUrl,
                                             Map<String, String> extraEnvVars,
                                             Long overrideTimeoutSeconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);

            // 根据 maxTurns 调整超时：每轮约 30s，或使用 override（A2A 异步 = 30 min）
            long timeoutSeconds = overrideTimeoutSeconds != null
                    ? overrideTimeoutSeconds
                    : Math.max(60, maxTurns * 30L);

            SyncQueryAccumulator state = new SyncQueryAccumulator(claudeSessionId);
            client.streamQuery(
                            prompt, cwd, claudeSessionId, model, maxTurns,
                            null, null, apiKey, authToken, baseUrl, "bypassPermissions", null,
                            null, null, extraEnvVars)
                    .doOnNext(sse -> consumeSyncEvent(state, sse))
                    .then()
                    .block(Duration.ofSeconds(timeoutSeconds));

            result.put("workerTaskId", state.workerTaskId);
            result.put("resultText", state.getResultText());
            result.put("claudeSessionId", state.claudeSessionId);
            result.put("costUsd", state.costUsd);
            result.put("durationMs", state.durationMs);
            result.put("inputTokens", state.inputTokens);
            result.put("outputTokens", state.outputTokens);
            result.put("numTurns", state.numTurns);
            result.put("model", state.model);
            if (state.error != null) {
                result.put("error", state.error);
                result.put("errorSource", "WORKER");
            }

            log.info("doSyncQuery completed: workerId={}, events={}, hasResult={}, hasError={}",
                    worker.getWorkerId(), state.eventCount, state.getResultText() != null, result.containsKey("error"));

        } catch (Exception e) {
            log.error("syncQuery failed: workerId={}, error={}", worker.getWorkerId(), e.getMessage(), e);
            result.put("error", e.getMessage());
            result.put("errorSource", "TRANSPORT");  // 传输层异常（超时、断连等）
            result.put("durationMs", System.currentTimeMillis() - startTime);
        }

        return result;
    }

    /**
     * 从工作目录的 defaultModelConfigId 解析 auth
     * @return [apiKey, authToken, baseUrl]（可能全 null）
     */
    private String[] resolveDirectoryAuth(String directoryId, String userId) {
        if (directoryId == null || directoryId.isEmpty()) {
            return new String[]{null, null, null};
        }
        WorkingDirectoryEntity dir = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId).orElse(null);
        if (dir == null || dir.getDefaultModelConfigId() == null) {
            return new String[]{null, null, null};
        }
        // 校验 Worker 是否有权使用该模型
        llmModelManager.validateModelAccessForWorker(dir.getDefaultModelConfigId(), dir.getWorkerId());
        LlmModelConfigDTO config = llmModelManager.getModelConfig(dir.getDefaultModelConfigId()).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getHasApiKey())) {
            return new String[]{null, null, null};
        }
        String apiKey = llmModelManager.getDecryptedApiKey(dir.getDefaultModelConfigId());
        return new String[]{apiKey, null, config.getBaseUrl()};
    }

    /**
     * 从工作目录的 defaultModelConfigId 解析环境变量
     */
    private Map<String, String> resolveDirectoryEnvVars(String directoryId, String userId) {
        if (directoryId == null || directoryId.isEmpty()) {
            return null;
        }
        WorkingDirectoryEntity dir = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId).orElse(null);
        if (dir == null) {
            return null;
        }

        Map<String, String> merged = new LinkedHashMap<>();

        // 1. LLM 模型配置上的环境变量
        if (dir.getDefaultModelConfigId() != null) {
            LlmModelConfigDTO config = llmModelManager.getModelConfig(dir.getDefaultModelConfigId()).orElse(null);
            if (config != null && config.getEnvVars() != null) {
                merged.putAll(config.getEnvVars());
            }
        }

        // 2. 目录级自定义环境变量（优先级更高，覆盖 LLM 配置的同名变量）
        if (dir.getCustomEnvVars() != null && !dir.getCustomEnvVars().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> custom = objectMapper.readValue(dir.getCustomEnvVars(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                merged.putAll(custom);
            } catch (Exception e) {
                log.warn("Failed to parse customEnvVars for directory {}: {}", directoryId, e.getMessage());
            }
        }

        return merged.isEmpty() ? null : merged;
    }

    @Override
    public String initDirectory(String userId, String workerId, String path, Map<String, String> files) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        // Worker 返回展开后的路径（如 ~/foggy-assistant → C:\Users\xxx\foggy-assistant）
        String expandedPath = path;
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> response = client.initDirectory(path, files).block(Duration.ofSeconds(30));
            if (response != null && response.get("path") != null) {
                expandedPath = (String) response.get("path");
            }
            log.info("Initialized directory on worker {}: path={} → {}", workerId, path, expandedPath);
        } catch (Exception e) {
            log.error("Failed to init directory on worker {}: {}", workerId, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize directory: " + e.getMessage(), e);
        }

        // 注册工作目录（如果已存在则返回现有 directoryId）
        Optional<WorkingDirectoryEntity> existing = directoryRepository
                .findByWorkerIdAndPathAndUserId(workerId, expandedPath, userId);
        if (existing.isPresent()) {
            return existing.get().getDirectoryId();
        }
        // 也检查原始路径（兼容旧数据）
        Optional<WorkingDirectoryEntity> existingOriginal = directoryRepository
                .findByWorkerIdAndPathAndUserId(workerId, path, userId);
        if (existingOriginal.isPresent()) {
            // 更新为展开后的路径
            WorkingDirectoryEntity old = existingOriginal.get();
            old.setPath(expandedPath);
            directoryRepository.save(old);
            return old.getDirectoryId();
        }

        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(IdGenerator.shortId());
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setProjectName("foggy-assistant");
        entity.setPath(expandedPath);
        entity.setDirectoryType("STANDARD");
        entity.setWorktree(false);
        directoryRepository.save(entity);
        log.info("Registered working directory: directoryId={}, path={}", entity.getDirectoryId(), path);

        return entity.getDirectoryId();
    }

    @Override
    public String initDirectory(String userId, String workerId, String path,
                                 Map<String, String> files, String projectName) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        // Worker 返回展开后的路径
        String expandedPath = path;
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> response = client.initDirectory(path, files).block(Duration.ofSeconds(30));
            if (response != null && response.get("path") != null) {
                expandedPath = (String) response.get("path");
            }
            log.info("Initialized directory on worker {}: path={} → {}", workerId, path, expandedPath);
        } catch (Exception e) {
            log.error("Failed to init directory on worker {}: {}", workerId, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize directory: " + e.getMessage(), e);
        }

        // 注册工作目录（如果已存在则返回现有 directoryId）
        Optional<WorkingDirectoryEntity> existing = directoryRepository
                .findByWorkerIdAndPathAndUserId(workerId, expandedPath, userId);
        if (existing.isPresent()) {
            return existing.get().getDirectoryId();
        }
        Optional<WorkingDirectoryEntity> existingOriginal = directoryRepository
                .findByWorkerIdAndPathAndUserId(workerId, path, userId);
        if (existingOriginal.isPresent()) {
            WorkingDirectoryEntity old = existingOriginal.get();
            old.setPath(expandedPath);
            directoryRepository.save(old);
            return old.getDirectoryId();
        }

        String effectiveName = (projectName != null && !projectName.isBlank())
                ? projectName : "foggy-assistant";
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(IdGenerator.shortId());
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setProjectName(effectiveName);
        entity.setPath(expandedPath);
        entity.setDirectoryType("STANDARD");
        entity.setWorktree(false);
        directoryRepository.save(entity);
        log.info("Registered working directory: directoryId={}, path={}, projectName={}",
                entity.getDirectoryId(), path, effectiveName);

        return entity.getDirectoryId();
    }

    @Override
    public void validateWorkerOwnership(String userId, String workerId) {
        validateWorkerAccess(userId, null, workerId);
    }

    @Override
    public void validateWorkerAccess(String userId, String tenantId, String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker.getUserId().equals(userId)) {
            return;
        }
        if (tenantId != null && !tenantId.isBlank() && tenantId.equals(worker.getTenantId())) {
            log.debug("validateWorkerAccess: tenant access granted for userId={}, tenantId={}, workerId={}",
                    userId, tenantId, workerId);
            return;
        }
        if (isSameTenant(userId, worker.getTenantId())) {
            log.debug("validateWorkerAccess: user tenant access granted for userId={}, workerId={}",
                    userId, workerId);
            return;
        }
        throw new IllegalArgumentException("Worker not found: " + workerId);
    }

    @Override
    public CodexConfig getCodexConfig(String workerId) {
        ClaudeWorkerEntity entity = workerService.getWorkerEntity(workerId);
        CodexConfig codexConfig = workerService.getDecryptedCodexConfig(entity);
        if (codexConfig != null && codexConfig.getBaseUrl() != null && !codexConfig.getBaseUrl().isBlank()) {
            return codexConfig;
        }
        if (entity.getBaseUrl() != null && !entity.getBaseUrl().isBlank()) {
            log.debug("getCodexConfig: using legacy worker baseUrl for workerId={}", workerId);
            return CodexConfig.builder()
                    .baseUrl(entity.getBaseUrl())
                    .authToken(workerService.getDecryptedToken(entity))
                    .build();
        }
        log.debug("getCodexConfig: no Codex endpoint configured for workerId={}", workerId);
        return null;
    }

    private Map<String, Object> workerToMap(WorkerDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workerId", dto.getWorkerId());
        map.put("name", dto.getName());
        map.put("baseUrl", dto.getBaseUrl());
        map.put("authMode", dto.getAuthMode());
        map.put("status", dto.getStatus());
        map.put("hostname", dto.getHostname());
        map.put("workerVersion", dto.getWorkerVersion());
        map.put("lastHeartbeat", dto.getLastHeartbeat());
        map.put("createdAt", dto.getCreatedAt());
        return map;
    }

    private Map<String, Object> taskToMap(TaskDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", dto.getTaskId());
        map.put("workerTaskId", dto.getWorkerTaskId());
        map.put("sessionId", dto.getSessionId());
        map.put("workerId", dto.getWorkerId());
        map.put("prompt", dto.getPrompt());
        map.put("cwd", dto.getCwd());
        map.put("status", dto.getStatus());
        map.put("claudeSessionId", dto.getClaudeSessionId());
        map.put("costUsd", dto.getCostUsd());
        map.put("inputTokens", dto.getInputTokens());
        map.put("outputTokens", dto.getOutputTokens());
        map.put("durationMs", dto.getDurationMs());
        map.put("numTurns", dto.getNumTurns());
        map.put("model", dto.getModel());
        map.put("resultText", dto.getResultText());
        map.put("errorMessage", dto.getErrorMessage());
        map.put("lastAckedSeq", dto.getLastAckedSeq());
        map.put("createdAt", dto.getCreatedAt());
        return map;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private java.math.BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof java.math.BigDecimal bd) return bd;
        if (value instanceof Number num) return java.math.BigDecimal.valueOf(num.doubleValue());
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number num) return num.longValue();
        return null;
    }

    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number num) return num.intValue();
        return null;
    }

    private void consumeSyncEvent(SyncQueryAccumulator acc, org.springframework.http.codec.ServerSentEvent<String> sse) {
        String data = sse.data();
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            WorkerEvent event = objectMapper.readValue(data, WorkerEvent.class);
            acc.eventCount++;
            if (event.getTaskId() != null && !event.getTaskId().isBlank()) {
                acc.workerTaskId = event.getTaskId();
            }
            if (event.getSessionId() != null && !event.getSessionId().isBlank()) {
                acc.claudeSessionId = event.getSessionId();
            }
            if (event.getModel() != null && !event.getModel().isBlank()) {
                acc.model = event.getModel();
            }
            if ("result".equals(event.getType())) {
                acc.resultText = event.getContent() != null ? event.getContent() : event.getResult();
                acc.costUsd = event.getCostUsd();
                acc.durationMs = event.getDurationMs();
                acc.inputTokens = event.getInputTokens();
                acc.outputTokens = event.getOutputTokens();
                acc.numTurns = event.getNumTurns();
            } else if ("assistant_text".equals(event.getType())) {
                if (event.getContent() != null) {
                    acc.assistantText.append(event.getContent());
                }
            } else if ("error".equals(event.getType())) {
                acc.error = event.getError();
            }
        } catch (Exception e) {
            log.debug("Failed to parse sync query event: {}", e.getMessage());
        }
    }

    private static final class SyncQueryAccumulator {
        private String workerTaskId;
        private String claudeSessionId;
        private String model;
        private java.math.BigDecimal costUsd;
        private Long durationMs;
        private Long inputTokens;
        private Long outputTokens;
        private Integer numTurns;
        private String resultText;
        private String error;
        private int eventCount;
        private final StringBuilder assistantText = new StringBuilder();

        private SyncQueryAccumulator(String initialClaudeSessionId) {
            this.claudeSessionId = initialClaudeSessionId;
        }

        private String getResultText() {
            if (resultText != null) {
                return resultText;
            }
            return assistantText.isEmpty() ? null : assistantText.toString();
        }
    }

    // ── Shared File Operations ──

    @Override
    public Map<String, Object> listFiles(String userId, String directoryId, String subPath) {
        WorkingDirectoryEntity dir = getDirectoryEntityChecked(userId, directoryId);
        String fullPath = buildSafePath(dir.getPath(), subPath);
        ClaudeWorkerClient client = resolveClient(dir.getWorkerId());
        return client.listFiles(fullPath, false).block(Duration.ofSeconds(15));
    }

    @Override
    public Map<String, Object> readFile(String userId, String directoryId, String subPath) {
        if (subPath == null || subPath.isBlank()) {
            throw new IllegalArgumentException("subPath is required for readFile");
        }
        WorkingDirectoryEntity dir = getDirectoryEntityChecked(userId, directoryId);
        String fullPath = buildSafePath(dir.getPath(), subPath);
        ClaudeWorkerClient client = resolveClient(dir.getWorkerId());
        return client.readFileContent(fullPath).block(Duration.ofSeconds(15));
    }

    @Override
    public Map<String, Object> searchFiles(String userId, String directoryId, String query) {
        WorkingDirectoryEntity dir = getDirectoryEntityChecked(userId, directoryId);
        ClaudeWorkerClient client = resolveClient(dir.getWorkerId());
        return client.searchFiles(dir.getPath(), query, 100).block(Duration.ofSeconds(15));
    }

    private WorkingDirectoryEntity getDirectoryEntityChecked(String userId, String directoryId) {
        return directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
    }

    private ClaudeWorkerClient resolveClient(String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        return workerService.createClient(worker);
    }

    private static String buildSafePath(String basePath, String subPath) {
        if (subPath == null || subPath.isBlank()) return basePath;
        if (subPath.contains("..")) {
            throw new IllegalArgumentException("subPath must not contain '..'");
        }
        String normalized = subPath.replace("\\", "/");
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        return basePath + "/" + normalized;
    }

    /**
     * 判断 userId 对应的用户是否与指定 tenantId 属于同一租户
     * 用于 Open API / A2A 场景：员工通过租户级 Agent 访问管理员创建的 Worker
     */
    private boolean isSameTenant(String userId, String workerTenantId) {
        if (workerTenantId == null) return false;
        return userAuthService.getUser(userId)
                .map(UserDTO::getTenantId)
                .filter(workerTenantId::equals)
                .isPresent();
    }
}

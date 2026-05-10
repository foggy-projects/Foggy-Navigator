package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Codex 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodexTaskService implements TaskQueryProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final CodexTaskRepository taskRepository;
    private final WorkerManagementFacade workerManagementFacade;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    @Nullable
    private SessionManager sessionManager;

    @Autowired(required = false)
    @Nullable
    private LlmModelManager llmModelManager;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private SessionEntityRepository sessionEntityRepository;

    @Autowired(required = false)
    @Nullable
    private CodexCodingAgentRepository codingAgentRepository;

    @Autowired
    @Lazy
    private CodexStreamRelay streamRelay;

    /**
     * 创建并启动 Codex 任务
     */
    @Transactional
    public CodexTaskDTO createTask(String userId, String tenantId, CreateCodexTaskForm form) {
        // 如果 form 携带 sessionId（由 ContextResolvingA2aAgent 传入），则复用已有会话
        String existingSessionId = form.getSessionId();
        if (existingSessionId != null && existingSessionId.isBlank()) {
            existingSessionId = null;
        }
        return createAndStartTask(userId, tenantId, form, existingSessionId);
    }

    @Override
    @Transactional
    public DispatchTaskDTO resumeTask(String userId, String tenantId, java.util.Map<String, Object> params) {
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setImages((String) params.get("images"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }

        // codexThreadId 从 SessionEntity.providerStateJson 恢复，不再从 request 透传
        String sessionId = (String) params.get("sessionId");
        if (sessionId != null && !sessionId.isBlank() && sessionEntityRepository != null) {
            String codexThreadId = readJsonValue(
                    sessionEntityRepository.findById(sessionId)
                            .map(SessionEntity::getProviderStateJson).orElse(null),
                    "codexThreadId");
            form.setCodexThreadId(codexThreadId);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 sessionId");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 workerId");
        }

        workerManagementFacade.validateWorkerOwnership(userId, form.getWorkerId());

        if (form.getCodexThreadId() == null || form.getCodexThreadId().isBlank()) {
            // Platform-only rewind clears the native Codex thread. Continue by starting
            // a new Codex thread while reusing the Navigator session.
            validateExistingSession(userId, sessionId);
            CodexTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
            return getTaskById(task.getTaskId()).orElseThrow();
        }

        if (!taskRepository.existsByCodexThreadIdAndWorkerIdAndUserId(
                form.getCodexThreadId(), form.getWorkerId(), userId)) {
            throw new IllegalArgumentException("Codex 会话不存在或不属于该 Worker: " + form.getCodexThreadId());
        }
        if (taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndStatus(
                form.getCodexThreadId(), form.getWorkerId(), userId, "RUNNING")) {
            throw new IllegalStateException("该会话正在运行任务，请等待完成或终止后再继续");
        }

        validateExistingSession(userId, sessionId);
        CodexTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
        return getTaskById(task.getTaskId()).orElseThrow();
    }

    private CodexTaskDTO createAndStartTask(String userId, String tenantId,
                                            CreateCodexTaskForm form, String existingSessionId) {
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getPrompt() == null || form.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        // 验证 Worker 存在且属于该用户（通过 WorkerManagementFacade SPI）
        workerManagementFacade.validateWorkerOwnership(userId, form.getWorkerId());

        String cwd = form.getCwd();
        if ((cwd == null || cwd.isBlank())
                && form.getDirectoryId() != null
                && !form.getDirectoryId().isBlank()) {
            cwd = workerManagementFacade.getDirectoryPath(userId, form.getDirectoryId());
        }
        // Codex CLI (Rust) 不接受 Windows 反斜杠路径，需转为正斜杠
        if (cwd != null) {
            cwd = cwd.replace('\\', '/');
        }

        String effectiveAgentId = resolveLogicalAgentId(form.getAgentId(), existingSessionId);

        String taskId = IdGenerator.shortId();

        String sessionId = resolveSessionId(userId, tenantId, form.getPrompt(), existingSessionId, effectiveAgentId);

        String effectiveModelConfigId = resolveEffectiveModelConfigId(form.getModelConfigId(), effectiveAgentId);
        String modelConfigSource = resolveModelConfigSource(form.getModelConfigId(), effectiveAgentId);
        ModelResolution effectiveModelResolution = resolveEffectiveModel(
                form.getModel(), effectiveAgentId, effectiveModelConfigId);

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(form.getDirectoryId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setResolvedAgentId(effectiveAgentId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setModel(effectiveModelResolution.model());
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(form.getCodexThreadId());

        persistTask(entity);
        log.info("Created Codex task: taskId={}, workerId={}, sessionId={}", taskId, form.getWorkerId(), sessionId);

        // 解析 auth + envVars（apiKey + baseUrl + 环境变量，无配置时 Worker 使用本地凭证）
        CodexAuthResult auth = resolveCodexAuth(effectiveModelConfigId);
        log.info(
                "Resolved Codex task auth: taskId={}, agentId={}, modelConfigId={}, modelConfigSource={}, model={}, modelSource={}, hasApiKey={}, baseUrl={}, envVarKeys={}",
                taskId,
                effectiveAgentId,
                effectiveModelConfigId,
                modelConfigSource,
                entity.getModel(),
                effectiveModelResolution.source(),
                auth.apiKey != null && !auth.apiKey.isBlank(),
                auth.baseUrl,
                auth.envVars != null ? auth.envVars.keySet() : List.of()
        );

        // 发布统一事件触发 CodexStreamRelay（通过 providerType 条件过滤）
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        putIfNotBlank(providerConfig, "codexThreadId", form.getCodexThreadId());
        putIfNotBlank(providerConfig, "images", form.getImages());
        putIfNotBlank(providerConfig, "baseUrl", auth.baseUrl);
        if (auth.envVars != null && !auth.envVars.isEmpty()) {
            providerConfig.put("extraEnvVars", auth.envVars);
        }

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .prompt(form.getPrompt()).cwd(cwd)
                .model(entity.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(auth.apiKey).providerType(AGENT_ID)
                .providerConfig(providerConfig)
                .build());

        return toDTO(entity);
    }

    /**
     * 创建受追踪的同步任务记录（由 SPI syncQueryTracked 调用）
     */
    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                         String prompt, String cwd, String directoryId,
                                         String codexThreadId) {
        String taskId = IdGenerator.shortId();
        // Codex CLI (Rust) 不接受 Windows 反斜杠路径
        String normalizedCwd = cwd != null ? cwd.replace('\\', '/') : null;

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setPrompt(prompt);
        entity.setCwd(normalizedCwd);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(codexThreadId);

        persistTask(entity);
        log.info("Created tracked sync Codex task: taskId={}, sessionId={}", taskId, sessionId);
        return taskId;
    }

    /**
     * 记录 Worker 侧任务元数据与流消费进度。
     */
    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("recordWorkerProgress: task not found: {}", taskId);
            return;
        }

        if (workerTaskId != null && !workerTaskId.isBlank()) {
            entity.setWorkerTaskId(workerTaskId);
        }
        if (codexThreadId != null && !codexThreadId.isBlank()) {
            entity.setCodexThreadId(codexThreadId);
        }
        if (model != null && !model.isBlank()) {
            entity.setModel(model);
        }
        if (ackSeq != null) {
            Integer current = entity.getLastAckedSeq();
            entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
        }
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
    }

    /**
     * 获取任务详情
     */
    public CodexTaskDTO getTask(String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDTO(entity);
    }

    /**
     * 获取任务 Entity（内部使用）
     */
    public CodexTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    /**
     * 列出用户的所有任务
     */
    public List<CodexTaskDTO> listTasks(String userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 列出 Worker 下的任务
     */
    public List<CodexTaskDTO> listTasksByWorker(String userId, String workerId) {
        return taskRepository.findByWorkerIdAndUserId(workerId, userId).stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public void cancelTask(String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if ("RUNNING".equals(entity.getStatus()) || "AWAITING_PERMISSION".equals(entity.getStatus())) {
            abortTask(taskId);
        }
    }

    /**
     * 检查指定 Codex 会话是否有正在运行的任务（并发保护）
     */
    public boolean hasRunningTask(String codexThreadId, String workerId, String userId) {
        return taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndStatus(
                codexThreadId, workerId, userId, "RUNNING");
    }

    /**
     * 中止任务（完整流程：terminal guard + doAbortWorkerTask）。
     * Provider Controller 和 SPI 入口调用此方法。
     */
    @Transactional
    public void abortTask(String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if ("COMPLETED".equals(entity.getStatus()) || "FAILED".equals(entity.getStatus())
                || "ABORTED".equals(entity.getStatus())) {
            log.warn("Task {} is already in terminal state: {}", taskId, entity.getStatus());
            return;
        }

        String remoteTaskId = entity.getWorkerTaskId();
        doAbortWorkerTask(taskId, remoteTaskId);
    }

    /**
     * 远端中止 + 流清理 + 状态落库 + 事件发布。
     * <p>
     * 由 {@code CodexWorkerInnerA2aAgent.abortWorkerTask()} 和 {@code abortTask()} 复用。
     * 不包含 terminal-state guard（调用方负责）。
     *
     * @param taskId       平台侧 taskId
     * @param remoteTaskId 已解析的远端 Worker 任务标识（可能为 null，由装饰层通过 resolveRemoteTaskId 提供）
     */
    @Transactional
    public void doAbortWorkerTask(String taskId, String remoteTaskId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        // 通知远端 Worker 中止（复用 streamRelay 的远端中止能力）
        streamRelay.abortRemoteTask(entity);
        streamRelay.abortStream(taskId);

        String previousStatus = entity.getStatus();
        entity.setStatus("ABORTED");
        persistTask(entity);
        log.info("Aborted Codex task: taskId={}, previousStatus={}", taskId, previousStatus);
        publishStatusChange(entity, previousStatus);
    }

    /**
     * 标记任务完成
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String codexThreadId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("completeTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        entity.setStatus("COMPLETED");
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        if (resultText != null) entity.setResultText(resultText);
        if (costUsd != null) entity.setCostUsd(costUsd);
        if (inputTokens != null) entity.setInputTokens(inputTokens);
        if (outputTokens != null) entity.setOutputTokens(outputTokens);
        if (durationMs != null) entity.setDurationMs(durationMs);
        if (numTurns != null) entity.setNumTurns(numTurns);
        if (model != null) entity.setModel(model);
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());

        persistTask(entity);
        log.info("Completed Codex task: taskId={}, cost={}", taskId, costUsd);
        publishStatusChange(entity, previousStatus);
    }

    /**
     * 标记任务失败
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String codexThreadId, String errorMessage) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("failTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        entity.setStatus("FAILED");
        entity.setErrorMessage(errorMessage);
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        entity.setLastAliveAt(LocalDateTime.now());

        persistTask(entity);
        log.info("Failed Codex task: taskId={}, error={}", taskId, errorMessage);
        publishStatusChange(entity, previousStatus);
    }

    /**
     * 更新 Codex Thread ID
     */
    @Transactional
    public void updateCodexThreadId(String taskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity != null && codexThreadId != null) {
            entity.setCodexThreadId(codexThreadId);
            persistTask(entity);
        }
    }

    // ── TaskQueryProvider 实现 ──

    @Override
    public String getProviderType() {
        return AGENT_ID;
    }

    @Override
    public DispatchTaskDTO createTaskDirect(java.util.Map<String, Object> params,
                                             String userId, String tenantId) {
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setImages((String) params.get("images"));
        form.setCodexThreadId((String) params.get("codexThreadId"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        CodexTaskDTO dto = createTask(userId, tenantId, form);
        return getTaskById(dto.getTaskId()).orElseThrow();
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskById(String taskId) {
        return taskRepository.findByTaskId(taskId).map(this::toDispatchDTO);
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId).map(this::toDispatchDTO);
    }

    @Override
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        return taskRepository.findBySessionId(sessionId).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, List.of("RUNNING", "AWAITING_PERMISSION")).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public Object listTasksPaged(String userId, int page, int size, String state) {
        List<CodexTaskEntity> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public Object listTasksByDirectoryPaged(String userId, String directoryId, int page, int size, String state) {
        List<CodexTaskEntity> tasks = taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public Object searchSessions(String userId, String keyword, String workerId,
                                 String directoryId, int page, int size) {
        boolean hasFilter = (keyword != null && !keyword.isBlank())
                || (workerId != null && !workerId.isBlank())
                || (directoryId != null && !directoryId.isBlank());
        if (!hasFilter) {
            return Map.of("results", List.of(), "total", 0L, "page", page, "size", size);
        }

        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(
                taskRepository.findByUserIdOrderByCreatedAtDesc(userId)
        ).values());

        List<Map<String, Object>> filtered = sessions.stream()
                .filter(tasks -> matchesSessionFilters(tasks, normalizedKeyword, workerId, directoryId))
                .map(this::toSearchResult)
                .sorted((a, b) -> compareNullableTime((LocalDateTime) b.get("updatedAt"), (LocalDateTime) a.get("updatedAt")))
                .toList();

        long total = filtered.size();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return Map.of(
                "results", filtered.subList(from, to),
                "total", total,
                "page", page,
                "size", size
        );
    }

    private DispatchTaskDTO toDispatchDTO(CodexTaskEntity entity) {
        String agentId = resolveLogicalAgentId(entity);
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(agentId)
                .providerType(AGENT_ID)
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .directoryId(entity.getDirectoryId())
                .status(entity.getStatus())
                .model(entity.getModel())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .resultText(entity.getResultText())
                .errorMessage(entity.getErrorMessage())
                .lastAckedSeq(entity.getLastAckedSeq())
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                // Codex-specific
                .codexThreadId(entity.getCodexThreadId())
                .build();
    }

    @Override
    public void deleteTask(String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if ("RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("Cannot delete a running task. Please abort it first.");
        }

        // Soft-delete session
        String sessionId = entity.getSessionId();
        if (sessionId != null && sessionEntityRepository != null) {
            try {
                sessionEntityRepository.findById(sessionId).ifPresent(session -> {
                    session.setDeletedAt(java.time.LocalDateTime.now());
                    sessionEntityRepository.save(session);
                });
            } catch (Exception e) {
                log.warn("Failed to soft-delete session: sessionId={}", sessionId, e);
            }
        }

        taskRepository.delete(entity);
        if (sessionId != null && sessionManager != null) {
            try {
                sessionManager.deleteSession(sessionId);
            } catch (Exception e) {
                log.warn("Failed to delete session from SessionManager: sessionId={}", sessionId, e);
            }
        }
        log.info("Codex task deleted: taskId={}, userId={}", taskId, userId);
    }

    @Override
    public Object resyncTask(String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be resynced, current: " + entity.getStatus());
        }
        if (entity.getWorkerTaskId() == null) {
            throw new IllegalStateException("No worker task ID, cannot resync");
        }

        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        persistTask(entity);
        log.info("Resync: reset task {} to RUNNING, attempting SSE reconnect", taskId);

        try {
            streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
        } catch (Exception e) {
            log.warn("Resync: SSE reconnect failed for task {}: {}", taskId, e.getMessage());
        }

        return Map.of("status", "RESYNCED", "action", "RECONNECTED", "taskId", taskId);
    }

    private Map<String, Object> buildSessionPage(List<CodexTaskEntity> tasks, int page, int size, String interactionState) {
        Set<String> states = parseInteractionStates(interactionState);
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(tasks).values());
        if (!states.isEmpty()) {
            sessions = sessions.stream()
                    .filter(sessionTasks -> states.contains(deriveInteractionState(sessionTasks.get(0).getStatus())))
                    .toList();
        }

        long totalSessions = sessions.size();
        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<DispatchTaskDTO> content = sessions.subList(from, to).stream()
                .flatMap(Collection::stream)
                .map(this::toDispatchDTO)
                .toList();

        return Map.of(
                "content", content,
                "totalSessions", totalSessions,
                "page", page,
                "size", size
        );
    }

    private Map<String, List<CodexTaskEntity>> groupTasksBySession(List<CodexTaskEntity> tasks) {
        Map<String, List<CodexTaskEntity>> grouped = new LinkedHashMap<>();
        for (CodexTaskEntity task : tasks) {
            String sessionKey = (task.getSessionId() != null && !task.getSessionId().isBlank())
                    ? task.getSessionId()
                    : "task:" + task.getTaskId();
            grouped.computeIfAbsent(sessionKey, ignored -> new ArrayList<>()).add(task);
        }
        return grouped;
    }

    private Set<String> parseInteractionStates(String interactionState) {
        if (interactionState == null || interactionState.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(interactionState.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchesSessionFilters(List<CodexTaskEntity> tasks, String keyword, String workerId, String directoryId) {
        CodexTaskEntity latestTask = tasks.get(0);
        if (workerId != null && !workerId.isBlank() && !workerId.equals(latestTask.getWorkerId())) {
            return false;
        }
        if (directoryId != null && !directoryId.isBlank() && !directoryId.equals(latestTask.getDirectoryId())) {
            return false;
        }
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return tasks.stream().anyMatch(task -> containsIgnoreCase(task.getPrompt(), keyword))
                || tasks.stream().anyMatch(task -> containsIgnoreCase(task.getResultText(), keyword));
    }

    private Map<String, Object> toSearchResult(List<CodexTaskEntity> tasks) {
        CodexTaskEntity latestTask = tasks.get(0);
        CodexTaskEntity earliestTask = tasks.get(tasks.size() - 1);
        BigDecimal totalCost = tasks.stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime updatedAt = tasks.stream()
                .map(CodexTaskEntity::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(latestTask.getUpdatedAt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", latestTask.getSessionId());
        result.put("workerId", latestTask.getWorkerId());
        result.put("directoryId", latestTask.getDirectoryId());
        result.put("firstPrompt", truncate(earliestTask.getPrompt(), 200));
        result.put("customTitle", null);
        result.put("tags", List.of());
        result.put("interactionState", deriveInteractionState(latestTask.getStatus()));
        result.put("latestTaskId", latestTask.getTaskId());
        result.put("latestStatus", latestTask.getStatus());
        result.put("model", latestTask.getModel());
        result.put("cwd", latestTask.getCwd());
        result.put("source", latestTask.getSource());
        result.put("totalCost", totalCost);
        result.put("createdAt", earliestTask.getCreatedAt());
        result.put("updatedAt", updatedAt);
        return result;
    }

    private String deriveInteractionState(String taskStatus) {
        if ("RUNNING".equals(taskStatus) || "PENDING".equals(taskStatus)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus)
                || "ABORTED".equals(taskStatus) || "AWAITING_PERMISSION".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private void publishStatusChange(CodexTaskEntity entity, String previousStatus) {
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(AGENT_ID)
                .status(entity.getStatus())
                .previousStatus(previousStatus)
                .errorMessage(entity.getErrorMessage())
                .interactionState(deriveInteractionState(entity.getStatus()))
                .build());
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static final String AGENT_ID = "codex-worker";

    private CodexTaskEntity persistTask(CodexTaskEntity entity) {
        CodexTaskEntity saved = taskRepository.save(entity);
        syncSessionTask(saved);
        syncSessionProjection(saved);
        return saved;
    }

    private void syncSessionTask(CodexTaskEntity entity) {
        if (sessionTaskRepository == null) {
            return;
        }
        String agentId = resolveLogicalAgentId(entity);

        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskId(entity.getTaskId())
                .orElseGet(SessionTaskEntity::new);
        sessionTask.setTaskId(entity.getTaskId());
        sessionTask.setSessionId(entity.getSessionId());
        sessionTask.setProviderType(AGENT_ID);
        sessionTask.setProviderTaskId(entity.getWorkerTaskId());
        sessionTask.setWorkerId(entity.getWorkerId());
        sessionTask.setUserId(entity.getUserId());
        sessionTask.setTenantId(entity.getTenantId());
        sessionTask.setAgentId(agentId);
        sessionTask.setDirectoryId(entity.getDirectoryId());
        sessionTask.setPrompt(entity.getPrompt());
        sessionTask.setCwd(entity.getCwd());
        sessionTask.setStatus(entity.getStatus());
        sessionTask.setModel(entity.getModel());
        sessionTask.setCostUsd(entity.getCostUsd());
        sessionTask.setInputTokens(entity.getInputTokens());
        sessionTask.setOutputTokens(entity.getOutputTokens());
        sessionTask.setDurationMs(entity.getDurationMs());
        sessionTask.setNumTurns(entity.getNumTurns());
        sessionTask.setResultText(entity.getResultText());
        sessionTask.setErrorMessage(entity.getErrorMessage());
        sessionTask.setSource(entity.getSource());
        sessionTask.setLastAckedSeq(entity.getLastAckedSeq());
        sessionTask.setLastAliveAt(entity.getLastAliveAt());
        sessionTask.setCreatedAt(entity.getCreatedAt());
        sessionTask.setUpdatedAt(entity.getUpdatedAt());
        sessionTask.setTaskStateJson(buildCodexTaskStateJson(entity));
        sessionTaskRepository.save(sessionTask);
    }

    private void syncSessionProjection(CodexTaskEntity entity) {
        if (sessionEntityRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        String agentId = resolveLogicalAgentId(entity);

        SessionEntity session = sessionEntityRepository.findById(entity.getSessionId())
                .orElseGet(() -> createSessionProjection(entity));
        session.setUserId(firstNonBlank(session.getUserId(), entity.getUserId()));
        session.setTenantId(firstNonBlank(session.getTenantId(), entity.getTenantId()));
        session.setAgentId(firstNonBlank(session.getAgentId(), agentId));
        session.setProviderType(AGENT_ID);
        session.setStatus(firstNonBlank(session.getStatus(), "ACTIVE"));
        session.setCurrentWorkerId(firstNonBlank(entity.getWorkerId(), session.getCurrentWorkerId()));
        session.setCurrentDirectoryId(firstNonBlank(entity.getDirectoryId(), session.getCurrentDirectoryId()));
        session.setLatestTaskId(entity.getTaskId());
        session.setLatestModel(firstNonBlank(entity.getModel(), session.getLatestModel()));
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        session.setProviderStateJson(mergeJsonValue(session.getProviderStateJson(), "codexThreadId", entity.getCodexThreadId()));
        sessionEntityRepository.save(session);
    }

    private SessionEntity createSessionProjection(CodexTaskEntity entity) {
        String agentId = resolveLogicalAgentId(entity);
        SessionEntity session = new SessionEntity();
        session.setId(entity.getSessionId());
        session.setUserId(entity.getUserId());
        session.setTenantId(entity.getTenantId());
        session.setAgentId(agentId);
        session.setProviderType(AGENT_ID);
        session.setStatus("ACTIVE");
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        session.setCurrentWorkerId(entity.getWorkerId());
        session.setCurrentDirectoryId(entity.getDirectoryId());
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        return session;
    }

    private String buildCodexTaskStateJson(CodexTaskEntity entity) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, "codexThreadId", entity.getCodexThreadId());
        if (state.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Codex task state", e);
        }
    }

    private String mergeJsonValue(String json, String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            try {
                values.putAll(OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Failed to parse session providerStateJson, recreating JSON: {}", json);
            }
        }
        if (value == null || value.isBlank()) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        if (values.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session provider state", e);
        }
    }

    private String readJsonValue(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object value = values.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to read key '{}' from JSON: {}", key, json);
            return null;
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void validateExistingSession(String userId, String sessionId) {
        if (sessionManager == null) {
            return;
        }
        Session session = sessionManager.getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
        }
    }

    @Override
    @Transactional
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if ("RUNNING".equals(task.getStatus()) || "AWAITING_PERMISSION".equals(task.getStatus())) {
            throw new IllegalStateException("Cannot rewind a running task");
        }

        String mode = params != null && params.get("mode") != null
                ? params.get("mode").toString()
                : "conversation_fork";
        if (!"conversation_fork".equals(mode)) {
            throw new UnsupportedOperationException("Codex only supports conversation rewind; file rewind is not supported");
        }
        if (task.getSessionId() == null || task.getSessionId().isBlank()) {
            throw new IllegalArgumentException("Task has no Navigator session ID");
        }

        int turnIndex = extractTurnIndex(params);
        String userPrompt = findUserPromptAtTurn(task.getSessionId(), turnIndex);
        truncateSessionMessagesQuietly(task.getSessionId(), turnIndex);
        clearCodexThreadId(task.getSessionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "rewound");
        result.put("taskId", taskId);
        result.put("userPrompt", userPrompt != null ? userPrompt : "");
        result.put("turnIndex", turnIndex);
        result.put("codexThreadId", null);
        return result;
    }

    private int extractTurnIndex(Map<String, Object> params) {
        if (params != null && params.get("turnIndex") instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return 1;
    }

    private String findUserPromptAtTurn(String sessionId, int turnIndex) {
        if (sessionManager == null) {
            return "";
        }
        try {
            int userTurn = 0;
            for (Message message : sessionManager.getAllMessages(sessionId)) {
                if (message != null && message.getRole() == MessageRole.USER) {
                    userTurn++;
                    if (userTurn == turnIndex) {
                        return message.getContent();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to find Codex rewind prompt for session {} turn {}: {}",
                    sessionId, turnIndex, e.getMessage());
        }
        return "";
    }

    private void truncateSessionMessagesQuietly(String sessionId, int fromUserTurnIndex) {
        if (sessionManager == null) {
            return;
        }
        try {
            int deleted = sessionManager.truncateMessagesFromTurn(sessionId, fromUserTurnIndex);
            log.info("Codex platform conversation rewind: sessionId={}, turn={}, deletedMessages={}",
                    sessionId, fromUserTurnIndex, deleted);
        } catch (Exception e) {
            log.warn("Failed to truncate Codex session {} from user turn {}: {}",
                    sessionId, fromUserTurnIndex, e.getMessage());
        }
    }

    private void clearCodexThreadId(String sessionId) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionEntityRepository.findById(sessionId).ifPresent(session -> {
            session.setProviderStateJson(mergeJsonValue(session.getProviderStateJson(), "codexThreadId", null));
            sessionEntityRepository.save(session);
        });
    }

    private String resolveLogicalAgentId(@Nullable String requestedAgentId, @Nullable String existingSessionId) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            String sessionAgentId = resolveSessionAgentId(existingSessionId);
            if (sessionAgentId != null && !sessionAgentId.isBlank()) {
                return sessionAgentId;
            }
        }
        // 没有真实逻辑 Agent 时返回 null，不再回退到 provider 常量（需求 26 约束）
        return null;
    }

    private String resolveLogicalAgentId(CodexTaskEntity entity) {
        if (entity.getResolvedAgentId() != null && !entity.getResolvedAgentId().isBlank()) {
            return entity.getResolvedAgentId();
        }
        if (sessionTaskRepository != null) {
            String sessionTaskAgentId = sessionTaskRepository.findByTaskId(entity.getTaskId())
                    .map(SessionTaskEntity::getAgentId)
                    .orElse(null);
            if (sessionTaskAgentId != null && !sessionTaskAgentId.isBlank()) {
                return sessionTaskAgentId;
            }
        }
        if (entity.getSessionId() != null && !entity.getSessionId().isBlank()) {
            String sessionAgentId = resolveSessionAgentId(entity.getSessionId());
            if (sessionAgentId != null && !sessionAgentId.isBlank()) {
                return sessionAgentId;
            }
        }
        // 没有真实逻辑 Agent 时返回 null，不再回退到 provider 常量（需求 26 约束）
        return null;
    }

    private String resolveSessionAgentId(String sessionId) {
        if (sessionEntityRepository != null) {
            return sessionEntityRepository.findById(sessionId)
                    .map(SessionEntity::getAgentId)
                    .orElse(null);
        }
        return null;
    }

    private String resolveSessionId(String userId, String tenantId, String prompt,
                                     String existingSessionId, String agentId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return createPlatformSession(userId, tenantId, prompt, agentId);
        }

        if (sessionManager == null) {
            log.warn("SessionManager not available, reusing Codex sessionId without persisting message: {}", existingSessionId);
            return existingSessionId;
        }

        sessionManager.addMessage(existingSessionId, Message.builder()
                .sessionId(existingSessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return existingSessionId;
    }

    /**
     * 创建平台 SessionEntity（补齐 Codex 之前缺失的会话记录）
     */
    private String createPlatformSession(String userId, String tenantId, String prompt, String agentId) {
        if (sessionManager == null) {
            log.warn("SessionManager not available, falling back to IdGenerator for Codex sessionId");
            return IdGenerator.shortId();
        }
        String title = prompt != null && prompt.length() > 100 ? prompt.substring(0, 100) : prompt;
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(agentId != null ? agentId : AGENT_ID)
                .providerType(AGENT_ID)
                .taskName(title)
                .build());
        // 记录用户 prompt 到会话消息
        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return sessionId;
    }

    /** Codex auth 解析结果 */
    private record CodexAuthResult(String apiKey, String baseUrl, Map<String, String> envVars) {
        static final CodexAuthResult EMPTY = new CodexAuthResult(null, null, null);
    }

    /**
     * 解析 Codex auth 配置：apiKey + baseUrl + envVars。
     * <p>
     * 两种模式：
     * - API Key 模式：modelConfig 配置了 apiKey → 解密返回，baseUrl/envVars 可选
     * - Subscription 模式：modelConfig 无 apiKey → Worker 使用本地 ~/.codex/auth.json
     * <p>
     * envVars 用于传递 Codex CLI 配置（如 model_context_window、model_auto_compact_token_limit）
     */
    private CodexAuthResult resolveCodexAuth(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return CodexAuthResult.EMPTY;
        }
        try {
            var modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig == null) {
                return CodexAuthResult.EMPTY;
            }
            String apiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
            String baseUrl = modelConfig.getBaseUrl();
            Map<String, String> envVars = modelConfig.getEnvVars();
            return new CodexAuthResult(apiKey, baseUrl, envVars);
        } catch (Exception e) {
            log.warn("Failed to resolve Codex auth from modelConfigId={}: {}", modelConfigId, e.getMessage());
            return CodexAuthResult.EMPTY;
        }
    }

    private String resolveEffectiveModelConfigId(String explicitModelConfigId, @Nullable String agentId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return explicitModelConfigId;
        }
        if (agentId == null || agentId.isBlank() || codingAgentRepository == null) {
            return null;
        }
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
        if (agentEntity == null) {
            return null;
        }
        String defaultModelConfigId = agentEntity.getDefaultModelConfigId();
        return (defaultModelConfigId == null || defaultModelConfigId.isBlank()) ? null : defaultModelConfigId;
    }

    private String resolveModelConfigSource(String explicitModelConfigId, @Nullable String agentId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return "request";
        }
        if (agentId == null || agentId.isBlank() || codingAgentRepository == null) {
            return "none";
        }
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
        if (agentEntity == null) {
            return "none";
        }
        String defaultModelConfigId = agentEntity.getDefaultModelConfigId();
        return (defaultModelConfigId == null || defaultModelConfigId.isBlank()) ? "none" : "agent-default";
    }

    private ModelResolution resolveEffectiveModel(String explicitModel, @Nullable String agentId, @Nullable String modelConfigId) {
        if (explicitModel != null && !explicitModel.isBlank()) {
            return new ModelResolution(explicitModel, "request");
        }
        if (agentId != null && !agentId.isBlank() && codingAgentRepository != null) {
            CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
            if (agentEntity != null && agentEntity.getDefaultModel() != null && !agentEntity.getDefaultModel().isBlank()) {
                return new ModelResolution(agentEntity.getDefaultModel(), "agent-default");
            }
        }
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return new ModelResolution(null, "none");
        }
        String model = llmModelManager.getModelConfig(modelConfigId)
                .map(LlmModelConfigDTO::getModelName)
                .filter(configModel -> !configModel.isBlank())
                .orElse(null);
        return new ModelResolution(model, model != null ? "model-config" : "none");
    }

    private record ModelResolution(@Nullable String model, String source) {
    }

    private CodexTaskDTO toDTO(CodexTaskEntity entity) {
        return CodexTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .status(entity.getStatus())
                .codexThreadId(entity.getCodexThreadId())
                .model(entity.getModel())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .resultText(entity.getResultText())
                .errorMessage(entity.getErrorMessage())
                .lastAckedSeq(entity.getLastAckedSeq())
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

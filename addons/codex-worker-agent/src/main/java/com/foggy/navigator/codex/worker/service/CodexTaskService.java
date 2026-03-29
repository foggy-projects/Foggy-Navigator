package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 创建并启动 Codex 任务
     */
    @Transactional
    public CodexTaskDTO createTask(String userId, String tenantId, CreateCodexTaskForm form) {
        return createAndStartTask(userId, tenantId, form, null);
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
        if (form.getCodexThreadId() == null || form.getCodexThreadId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 codexThreadId（需从 session 恢复）");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 sessionId");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 workerId");
        }

        workerManagementFacade.validateWorkerOwnership(userId, form.getWorkerId());

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
        entity.setModel(form.getModel());
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(form.getCodexThreadId());

        persistTask(entity);
        log.info("Created Codex task: taskId={}, workerId={}, sessionId={}", taskId, form.getWorkerId(), sessionId);

        // 解析 API Key（如有模型配置）
        String apiKey = resolveApiKey(form.getModelConfigId());

        // 发布统一事件触发 CodexStreamRelay（通过 providerType 条件过滤）
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        putIfNotBlank(providerConfig, "codexThreadId", form.getCodexThreadId());
        putIfNotBlank(providerConfig, "images", form.getImages());

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .prompt(form.getPrompt()).cwd(cwd)
                .model(form.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(apiKey).providerType(AGENT_ID)
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
     * 中止任务
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

        String previousStatus = entity.getStatus();
        entity.setStatus("ABORTED");
        persistTask(entity);
        log.info("Aborted Codex task: taskId={}", taskId);
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
        return AGENT_ID;
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
        return AGENT_ID;
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

    private String resolveApiKey(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return null;
        }
        try {
            var modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig == null) {
                return null;
            }
            if ("OPENAI_CODEX".equals(modelConfig.getWorkerBackend())
                    && (modelConfig.getBaseUrl() == null || modelConfig.getBaseUrl().isBlank())) {
                // Codex subscription mode should use local ~/.codex/auth.json instead of a platform API key.
                return null;
            }
            return llmModelManager.getDecryptedApiKey(modelConfigId);
        } catch (Exception e) {
            log.warn("Failed to resolve API key from modelConfigId={}: {}", modelConfigId, e.getMessage());
            return null;
        }
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

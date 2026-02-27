package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.dto.SessionPageDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.event.ClaudeTaskStartEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.foggy.navigator.common.util.IdGenerator;

/**
 * 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeTaskService {

    private static final String AGENT_ID = "claude-worker";

    private final ClaudeTaskRepository taskRepository;
    private final ClaudeWorkerService workerService;
    private final ConversationConfigService conversationConfigService;
    private final WorkingDirectoryService workingDirectoryService;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final LlmModelManager llmModelManager;
    private final UserAuthService userAuthService;

    /** Navigator API external URL, injected into CLI env as NAVIGATOR_API_BASE.
     *  Defaults to http://localhost:{server.port} when not explicitly configured. */
    @Value("${navigator.api.external-url:http://localhost:${server.port:8112}}")
    private String navigatorApiBaseUrl;

    /**
     * 创建任务
     */
    @Transactional
    public TaskDTO createTask(String userId, String tenantId, CreateTaskForm form) {
        // 1. 验证 Worker 归属
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }
        if (!"ONLINE".equals(worker.getStatus())) {
            throw new IllegalStateException("Worker is not online: " + worker.getStatus());
        }

        // 2. 如果指定了 directoryId，从目录解析 cwd 和 agentTeams
        String cwd = form.getCwd();
        String directoryId = form.getDirectoryId();
        String agentTeamsJson = form.getAgentTeamsJson();
        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryService.getDirectoryEntity(userId, directoryId);
            if (!dir.getWorkerId().equals(form.getWorkerId())) {
                throw new IllegalArgumentException("Directory does not belong to the specified worker");
            }
            cwd = dir.getPath();
            if ((agentTeamsJson == null || agentTeamsJson.isEmpty()) && dir.getAgentTeamsConfig() != null) {
                agentTeamsJson = dir.getAgentTeamsConfig();
            }
        }

        // 3. 创建 Foggy Session
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(AGENT_ID)
                .taskName(truncate(form.getPrompt(), 100))
                .build());

        // 3.5. 添加用户 prompt 作为 USER 消息
        sessionManager.addMessage(sessionId, com.foggy.navigator.agent.framework.session.Message.builder()
                .sessionId(sessionId)
                .role(com.foggy.navigator.agent.framework.session.MessageRole.USER)
                .content(form.getPrompt())
                .build());

        // 4. 持久化任务
        String taskId = IdGenerator.shortId();
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setDirectoryId(directoryId);
        entity.setFileCheckpointingEnabled(true);
        entity.setStatus("RUNNING");
        taskRepository.save(entity);

        log.info("Task created: taskId={}, sessionId={}, workerId={}, userId={}", taskId, sessionId, form.getWorkerId(), userId);

        // 5. 解析 per-conversation auth（含平台模型配置 fallback）
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, form.getModelConfigId());

        // 5.5. 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        // 6. 发布任务启动事件 → WorkerStreamRelay 监听
        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), cwd, null, form.getModel(), form.getMaxTurns(), agentTeamsJson,
                form.getImages(),
                authParams[0], authParams[1], authParams[2], form.getPermissionMode(),
                navigatorApiKey, navigatorApiBaseUrl));

        return toDTO(entity);
    }

    /**
     * 恢复任务（resume Claude Code 会话）
     */
    @Transactional
    public TaskDTO resumeTask(String userId, String tenantId, ResumeTaskForm form) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }

        // 如果指定了 directoryId，从目录解析 cwd 和 agentTeams
        String cwd = form.getCwd();
        String directoryId = form.getDirectoryId();
        String agentTeamsJson = form.getAgentTeamsJson();
        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryService.getDirectoryEntity(userId, directoryId);
            if (!dir.getWorkerId().equals(form.getWorkerId())) {
                throw new IllegalArgumentException("Directory does not belong to the specified worker");
            }
            cwd = dir.getPath();
            if ((agentTeamsJson == null || agentTeamsJson.isEmpty()) && dir.getAgentTeamsConfig() != null) {
                agentTeamsJson = dir.getAgentTeamsConfig();
            }
        }

        // Per-conversation: 复用已有 session 或创建新 session
        String sessionId;
        if (form.getSessionId() != null && !form.getSessionId().isEmpty()) {
            Session existing = sessionManager.getSession(form.getSessionId());
            if (existing == null || !existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("Session not found or access denied");
            }
            sessionId = existing.getId();
        } else {
            sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .agentId(AGENT_ID)
                    .taskName("Resume: " + truncate(form.getPrompt(), 80))
                    .build());
        }

        // 添加用户 prompt 作为 USER 消息
        sessionManager.addMessage(sessionId, com.foggy.navigator.agent.framework.session.Message.builder()
                .sessionId(sessionId)
                .role(com.foggy.navigator.agent.framework.session.MessageRole.USER)
                .content(form.getPrompt())
                .build());

        String taskId = IdGenerator.shortId();
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setDirectoryId(directoryId);
        entity.setClaudeSessionId(form.getClaudeSessionId());
        entity.setFileCheckpointingEnabled(true);
        entity.setStatus("RUNNING");
        taskRepository.save(entity);

        log.info("Task resumed: taskId={}, claudeSessionId={}, directoryId={}", taskId, form.getClaudeSessionId(), directoryId);

        // 解析 per-conversation auth（含平台模型配置 fallback）
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, form.getModelConfigId());

        // 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), cwd, form.getClaudeSessionId(),
                form.getModel(), form.getMaxTurns(), agentTeamsJson,
                null, authParams[0], authParams[1], authParams[2], form.getPermissionMode(),
                navigatorApiKey, navigatorApiBaseUrl));

        return toDTO(entity);
    }

    /**
     * 获取任务状态
     */
    public TaskDTO getTask(String userId, String taskId) {
        ClaudeTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDTO(entity);
    }

    /**
     * 列出用户的所有任务
     */
    public List<TaskDTO> listTasks(String userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 按会话分页列出用户的任务。
     * 每页包含 size 个会话（而非任务），返回这些会话的所有任务。
     */
    public SessionPageDTO listTasksBySession(String userId, int page, int size) {
        // 1. 获取当前页的 sessionIds（按最新任务时间排序）
        List<String> sessionIds = taskRepository.findDistinctSessionIdsByUser(userId, PageRequest.of(page, size));
        if (sessionIds.isEmpty()) {
            return SessionPageDTO.builder()
                    .content(List.of()).totalSessions(0).page(page).size(size).build();
        }
        // 2. 获取这些 session 的所有任务
        List<TaskDTO> tasks = taskRepository.findBySessionIdInAndUserIdOrderByCreatedAtDesc(sessionIds, userId)
                .stream().map(this::toDTO).toList();
        // 3. 获取会话总数
        long totalSessions = taskRepository.countDistinctSessionsByUser(userId);
        return SessionPageDTO.builder()
                .content(tasks).totalSessions(totalSessions).page(page).size(size).build();
    }

    /**
     * 按目录列出任务
     */
    public List<TaskDTO> listTasksByDirectory(String userId, String directoryId) {
        return taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 按目录、按会话分页列出任务
     */
    public SessionPageDTO listTasksByDirectorySession(String userId, String directoryId, int page, int size) {
        List<String> sessionIds = taskRepository.findDistinctSessionIdsByDirectory(directoryId, userId, PageRequest.of(page, size));
        if (sessionIds.isEmpty()) {
            return SessionPageDTO.builder()
                    .content(List.of()).totalSessions(0).page(page).size(size).build();
        }
        List<TaskDTO> tasks = taskRepository.findBySessionIdInAndUserIdOrderByCreatedAtDesc(sessionIds, userId)
                .stream().map(this::toDTO).toList();
        long totalSessions = taskRepository.countDistinctSessionsByDirectory(directoryId, userId);
        return SessionPageDTO.builder()
                .content(tasks).totalSessions(totalSessions).page(page).size(size).build();
    }

    /**
     * 扫描并填充 checkpoints（用于旧/同步会话无 checkpoint 数据时从 JSONL 补齐）
     */
    @Transactional
    public String scanAndPopulateCheckpoints(String taskId, List<Map<String, Object>> scannedCheckpoints) {
        ClaudeTaskEntity entity = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(scannedCheckpoints);
            entity.setCheckpoints(json);
            taskRepository.save(entity);
            log.info("Checkpoints populated from scan: taskId={}, count={}", taskId, scannedCheckpoints.size());
            return json;
        } catch (Exception e) {
            log.warn("Failed to serialize scanned checkpoints for task {}: {}", taskId, e.getMessage());
            throw new IllegalStateException("Failed to save checkpoints");
        }
    }

    /**
     * 追加 checkpoint 到任务
     */
    @Transactional
    public void addCheckpoint(String taskId, String checkpointId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String existing = entity.getCheckpoints();
            java.util.List<Map<String, Object>> list;
            if (existing != null && !existing.isEmpty()) {
                try {
                    list = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(existing, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse existing checkpoints for task {}: {}", taskId, e.getMessage());
                    list = new java.util.ArrayList<>();
                }
            } else {
                list = new java.util.ArrayList<>();
            }
            Map<String, Object> cp = new java.util.LinkedHashMap<>();
            cp.put("id", checkpointId);
            cp.put("turnIndex", list.size() + 1);
            cp.put("timestamp", java.time.LocalDateTime.now().toString());
            list.add(cp);
            try {
                entity.setCheckpoints(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list));
            } catch (Exception e) {
                log.warn("Failed to serialize checkpoints for task {}: {}", taskId, e.getMessage());
            }
            taskRepository.save(entity);
            log.debug("Checkpoint added: taskId={}, checkpointId={}, total={}", taskId, checkpointId, list.size());
        });
    }

    /**
     * 更新任务状态为完成
     */
    @Transactional
    public void completeTask(String taskId, String claudeSessionId, BigDecimal costUsd,
                              Long inputTokens, Long outputTokens, Long durationMs,
                              Integer numTurns, String model) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("COMPLETED");
            entity.setClaudeSessionId(claudeSessionId);
            entity.setCostUsd(costUsd);
            entity.setInputTokens(inputTokens);
            entity.setOutputTokens(outputTokens);
            entity.setDurationMs(durationMs);
            entity.setNumTurns(numTurns);
            entity.setModel(model);
            taskRepository.save(entity);
            log.info("Task completed: taskId={}, model={}, costUsd={}, durationMs={}", taskId, model, costUsd, durationMs);
        });
    }

    /**
     * 标记任务失败（保留 claudeSessionId 以便后续继续会话）
     */
    @Transactional
    public void failTask(String taskId, String claudeSessionId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            if (claudeSessionId != null && entity.getClaudeSessionId() == null) {
                entity.setClaudeSessionId(claudeSessionId);
            }
            taskRepository.save(entity);
            log.warn("Task failed: taskId={}, claudeSessionId={}, error={}", taskId, claudeSessionId, errorMessage);
        });
    }

    /**
     * 标记任务为等待权限审批
     */
    @Transactional
    public void setAwaitingPermission(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("AWAITING_PERMISSION");
            taskRepository.save(entity);
            log.info("Task awaiting permission: taskId={}", taskId);
        });
    }

    /**
     * 从等待权限恢复为运行中
     */
    @Transactional
    public void resumeFromPermission(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            if ("AWAITING_PERMISSION".equals(entity.getStatus())) {
                entity.setStatus("RUNNING");
                taskRepository.save(entity);
                log.info("Task resumed from permission: taskId={}", taskId);
            }
        });
    }

    /**
     * 标记任务已中止
     */
    @Transactional
    public void abortTask(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("ABORTED");
            taskRepository.save(entity);
            log.info("Task aborted: taskId={}", taskId);
        });
    }

    /**
     * 删除任务（仅允许删除已结束的任务）
     */
    @Transactional
    public void deleteTask(String userId, String taskId) {
        ClaudeTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        // 只允许删除已完成/失败/中止的任务，不能删除运行中的任务
        if ("RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("Cannot delete a running task. Please abort it first.");
        }

        // 同时删除关联的 Session 及其消息
        String sessionId = entity.getSessionId();
        taskRepository.delete(entity);
        if (sessionId != null) {
            try {
                sessionManager.deleteSession(sessionId);
                log.info("Associated session deleted: sessionId={}", sessionId);
            } catch (Exception e) {
                log.warn("Failed to delete associated session: sessionId={}", sessionId, e);
            }
        }
        log.info("Task deleted: taskId={}, userId={}", taskId, userId);
    }

    /**
     * 同步 Worker 本地会话为 ClaudeTask 记录
     * 对每个从 Worker 获取的会话，若不存在相同 claudeSessionId 的记录则创建
     */
    @Transactional
    public int syncLocalSessions(String userId, String tenantId, String workerId,
                                 List<Map<String, Object>> sessions) {
        if (sessions == null || sessions.isEmpty()) return 0;

        int created = 0;
        for (Map<String, Object> session : sessions) {
            String claudeSessionId = (String) session.get("session_id");
            if (claudeSessionId == null || claudeSessionId.isEmpty()) continue;

            // Dedup: skip if already synced
            if (taskRepository.existsByClaudeSessionIdAndWorkerId(claudeSessionId, workerId)) {
                continue;
            }

            String cwd = (String) session.get("cwd");
            String slug = (String) session.get("slug");

            // Match cwd to a WorkingDirectory for directoryId
            String directoryId = null;
            WorkingDirectoryEntity matchedDir = null;
            if (cwd != null && !cwd.isEmpty()) {
                var dirOpt = workingDirectoryRepository.findByWorkerIdAndPathAndUserId(workerId, cwd, userId);
                if (dirOpt.isEmpty()) {
                    String altCwd = cwd.contains("\\") ? cwd.replace('\\', '/') : cwd.replace('/', '\\');
                    dirOpt = workingDirectoryRepository.findByWorkerIdAndPathAndUserId(workerId, altCwd, userId);
                }
                if (dirOpt.isPresent()) {
                    matchedDir = dirOpt.get();
                    directoryId = matchedDir.getDirectoryId();
                }
            }

            String prompt = (slug != null && !slug.isEmpty()) ? slug : "(synced session)";

            // Create Navigator session
            String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .agentId(AGENT_ID)
                    .taskName(truncate(prompt, 100))
                    .build());

            // Create ClaudeTask entity
            String taskId = IdGenerator.shortId();
            ClaudeTaskEntity entity = new ClaudeTaskEntity();
            entity.setTaskId(taskId);
            entity.setSessionId(sessionId);
            entity.setWorkerId(workerId);
            entity.setUserId(userId);
            entity.setPrompt(prompt);
            entity.setCwd(cwd);
            entity.setDirectoryId(directoryId);
            entity.setClaudeSessionId(claudeSessionId);
            entity.setFileCheckpointingEnabled(false);
            entity.setStatus("COMPLETED");

            // Preserve original timestamps from JSONL
            LocalDateTime createdAt = parseSessionDateTime(session.get("created_at"));
            LocalDateTime updatedAt = parseSessionDateTime(session.get("updated_at"));
            if (createdAt != null) entity.setCreatedAt(createdAt);
            if (updatedAt != null) entity.setUpdatedAt(updatedAt);

            taskRepository.save(entity);

            // Auto-bind auth from WorkingDirectory default config
            if (matchedDir != null && matchedDir.getDefaultAuthMode() != null) {
                String[] dirAuth = workingDirectoryService.getDecryptedDefaultAuth(matchedDir);
                conversationConfigService.bindAuthFromDirectory(
                        sessionId, workerId, userId, dirAuth[0], dirAuth[1], dirAuth[2]);
            }

            created++;
        }

        // Backfill: patch existing tasks that have null directoryId
        int backfilled = backfillDirectoryIds(workerId, userId);
        if (backfilled > 0) {
            log.info("Backfilled directoryId for {} existing tasks on worker {}", backfilled, workerId);
        }

        log.info("Synced {} local sessions as tasks for worker {}", created, workerId);
        return created;
    }

    /**
     * Backfill directoryId for tasks that have a cwd but null directoryId.
     * Handles path separator differences (backslash vs forward slash).
     */
    private int backfillDirectoryIds(String workerId, String userId) {
        List<ClaudeTaskEntity> orphans = taskRepository.findByWorkerIdAndUserIdAndDirectoryIdIsNull(workerId, userId);
        int fixed = 0;
        for (ClaudeTaskEntity task : orphans) {
            String cwd = task.getCwd();
            if (cwd == null || cwd.isEmpty()) continue;

            var dirOpt = workingDirectoryRepository.findByWorkerIdAndPathAndUserId(workerId, cwd, userId);
            if (dirOpt.isEmpty()) {
                String altCwd = cwd.contains("\\") ? cwd.replace('\\', '/') : cwd.replace('/', '\\');
                dirOpt = workingDirectoryRepository.findByWorkerIdAndPathAndUserId(workerId, altCwd, userId);
            }
            if (dirOpt.isPresent()) {
                task.setDirectoryId(dirOpt.get().getDirectoryId());
                taskRepository.save(task);
                fixed++;
            }
        }
        return fixed;
    }

    /**
     * 解析 per-conversation auth 配置
     * 优先级：
     * 1. ConversationConfig 已绑定 auth → 解密 token 返回
     * 2. 用户选择的平台 LLM 模型配置（modelConfigId）→ 覆盖目录配置
     * 3. WorkingDirectory 默认 auth 继承
     * 4. 全 null（Worker 用 .env 或 claude login）
     * 返回 [apiKey, authToken, baseUrl]（可能全 null 表示使用 Worker 全局默认）
     */
    private String[] resolveAuth(String sessionId, String workerId, String userId,
                                 String directoryId, String modelConfigId) {
        ConversationConfigEntity config = conversationConfigService.getOrCreate(sessionId, workerId, userId);

        if (config.getAuthBoundAt() != null) {
            // Already bound — decrypt and return
            String decryptedToken = conversationConfigService.getDecryptedToken(config);
            String authMode = config.getAuthMode();
            String apiKey = null;
            String authToken = null;
            if ("API_KEY".equals(authMode) || "CUSTOM_ENDPOINT".equals(authMode)) {
                apiKey = decryptedToken;
            } else {
                authToken = decryptedToken;
            }
            return new String[]{apiKey, authToken, config.getBaseUrl()};
        }

        // 用户选择的平台模型配置 → 优先于目录默认 auth
        // 仅当 ConversationConfig 尚未绑定时，才使用并保存 modelConfigId 的 auth
        if (modelConfigId != null && !modelConfigId.isEmpty()) {
            LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig != null && Boolean.TRUE.equals(modelConfig.getHasApiKey())) {
                String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
                log.info("Auth resolved from platform model config: {}", modelConfig.getName());

                // 保存到 ConversationConfigEntity
                String authMode = (modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isEmpty())
                        ? "CUSTOM_ENDPOINT" : "API_KEY";
                conversationConfigService.bindAuthFromDirectory(
                        sessionId, workerId, userId, authMode, decryptedApiKey, modelConfig.getBaseUrl());

                return new String[]{decryptedApiKey, null, modelConfig.getBaseUrl()};
            }
        }

        // Fallback: auto-bind from WorkingDirectory default auth
        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryRepository
                    .findByDirectoryIdAndUserId(directoryId, userId).orElse(null);
            if (dir != null && dir.getDefaultAuthMode() != null) {
                String[] dirAuth = workingDirectoryService.getDecryptedDefaultAuth(dir);
                conversationConfigService.bindAuthFromDirectory(
                        sessionId, workerId, userId, dirAuth[0], dirAuth[1], dirAuth[2]);
                // Return decrypted values
                String apiKey = null;
                String authToken = null;
                if ("API_KEY".equals(dirAuth[0]) || "CUSTOM_ENDPOINT".equals(dirAuth[0])) {
                    apiKey = dirAuth[1];
                } else {
                    authToken = dirAuth[1];
                }
                return new String[]{apiKey, authToken, dirAuth[2]};
            }
        }

        return new String[]{null, null, null};
    }

    private LocalDateTime parseSessionDateTime(Object value) {
        if (value == null) return null;
        try {
            String str = value.toString();
            // Handle ISO datetime with timezone (e.g. "2026-02-15T10:30:00+00:00")
            if (str.contains("T")) {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(str);
                return odt.toLocalDateTime();
            }
        } catch (Exception e) {
            log.debug("Failed to parse session datetime: {}", value);
        }
        return null;
    }

    /**
     * 定时检查超时任务（每5分钟）
     * RUNNING 超过 4 小时的任务标记为 FAILED（与 Worker 端 hard timeout 对齐）
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void checkTimeoutTasks() {
        // RUNNING tasks: 4 hour timeout
        LocalDateTime runningCutoff = LocalDateTime.now().minusHours(4);
        List<ClaudeTaskEntity> timedOut = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", runningCutoff);
        for (ClaudeTaskEntity entity : timedOut) {
            entity.setStatus("FAILED");
            entity.setErrorMessage("Task timed out (exceeded 4 hours)");
            taskRepository.save(entity);
            log.warn("Task timed out: taskId={}, createdAt={}", entity.getTaskId(), entity.getCreatedAt());
        }

        // AWAITING_PERMISSION tasks: 30 minute timeout
        LocalDateTime permissionCutoff = LocalDateTime.now().minusMinutes(30);
        List<ClaudeTaskEntity> permissionTimedOut = taskRepository.findByStatusAndCreatedAtBefore("AWAITING_PERMISSION", permissionCutoff);
        for (ClaudeTaskEntity entity : permissionTimedOut) {
            entity.setStatus("FAILED");
            entity.setErrorMessage("Permission request timed out (exceeded 30 minutes)");
            taskRepository.save(entity);
            log.warn("Permission timed out: taskId={}, createdAt={}", entity.getTaskId(), entity.getCreatedAt());
        }

        int total = timedOut.size() + permissionTimedOut.size();
        if (total > 0) {
            log.info("Marked {} timed-out tasks as FAILED", total);
        }
    }

    /**
     * 截断会话消息：删除第 N 个 USER 消息及之后的所有消息（用于回退场景）
     */
    @Transactional
    public int truncateSessionMessages(String sessionId, int fromUserTurnIndex) {
        int deleted = sessionManager.truncateMessagesFromTurn(sessionId, fromUserTurnIndex);
        if (deleted > 0) {
            log.info("Truncated {} messages from session {} at user turn {}", deleted, sessionId, fromUserTurnIndex);
        }
        return deleted;
    }

    /**
     * 获取任务实体（内部使用）
     */
    public ClaudeTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private TaskDTO toDTO(ClaudeTaskEntity entity) {
        return TaskDTO.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .directoryId(entity.getDirectoryId())
                .status(entity.getStatus())
                .claudeSessionId(entity.getClaudeSessionId())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .model(entity.getModel())
                .errorMessage(entity.getErrorMessage())
                .checkpoints(entity.getCheckpoints())
                .fileCheckpointingEnabled(entity.getFileCheckpointingEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

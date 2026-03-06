package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import java.util.Arrays;
import com.foggy.navigator.claude.worker.model.dto.CliStatus;
import com.foggy.navigator.claude.worker.model.dto.MessageCount;
import com.foggy.navigator.claude.worker.model.dto.MessageSyncReport;
import com.foggy.navigator.claude.worker.model.dto.ResyncResult;
import com.foggy.navigator.claude.worker.model.dto.SessionPageDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.event.ClaudeTaskStartEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.model.entity.DeletedClaudeSessionEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
import com.foggy.navigator.claude.worker.repository.DeletedClaudeSessionRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final ConversationConfigRepository conversationConfigRepository;
    private final DeletedClaudeSessionRepository deletedSessionRepository;
    private final ClaudeWorkerService workerService;
    private final ConversationConfigService conversationConfigService;
    private final AgentTeamsConfigService agentTeamsConfigService;

    /** @Lazy 打破 ClaudeTaskService ↔ WorkerStreamRelay 的循环依赖 */
    @Autowired @Lazy
    private WorkerStreamRelay streamRelay;
    private final WorkingDirectoryService workingDirectoryService;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final LlmModelManager llmModelManager;
    private final UserAuthService userAuthService;
    private final TransactionTemplate txTemplate;

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
        String resolvedConfigId = null;
        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryService.getDirectoryEntity(userId, directoryId);
            if (!dir.getWorkerId().equals(form.getWorkerId())) {
                throw new IllegalArgumentException("Directory does not belong to the specified worker");
            }
            cwd = dir.getPath();
            if (agentTeamsJson == null || agentTeamsJson.isEmpty()) {
                // 优先级 2: 表单指定的命名配置 ID
                if (form.getAgentTeamsConfigId() != null && !form.getAgentTeamsConfigId().isEmpty()) {
                    AgentTeamsConfigEntity config = agentTeamsConfigService.getConfigEntity(form.getAgentTeamsConfigId());
                    if (config != null && config.getDirectoryId().equals(directoryId)) {
                        agentTeamsJson = config.getConfig();
                        resolvedConfigId = config.getConfigId();
                    }
                }
                // 优先级 3: 目录默认命名配置
                if (agentTeamsJson == null || agentTeamsJson.isEmpty()) {
                    Optional<AgentTeamsConfigEntity> defaultConfig = agentTeamsConfigService.getDefaultConfig(directoryId, userId);
                    if (defaultConfig.isPresent()) {
                        agentTeamsJson = defaultConfig.get().getConfig();
                        resolvedConfigId = defaultConfig.get().getConfigId();
                    }
                }
                // 优先级 4: 旧 legacy 字段（向后兼容）
                if (agentTeamsJson == null || agentTeamsJson.isEmpty()) {
                    if (dir.getAgentTeamsConfig() != null) {
                        agentTeamsJson = dir.getAgentTeamsConfig();
                    }
                }
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
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        entity.setAgentTeamsConfigId(resolvedConfigId);
        taskRepository.save(entity);

        log.info("Task created: taskId={}, sessionId={}, workerId={}, userId={}", taskId, sessionId, form.getWorkerId(), userId);
        publishStatusChange(entity, null);
        conversationConfigService.updateInteractionState(sessionId, "PROCESSING");

        // 4.5. 锁定 Agent Teams 配置到会话
        if (resolvedConfigId != null) {
            ConversationConfigEntity convConfig = conversationConfigService.getOrCreate(sessionId, form.getWorkerId(), userId);
            convConfig.setAgentTeamsConfigId(resolvedConfigId);
            conversationConfigRepository.save(convConfig);
        }

        // 5. 解析 per-conversation auth（含平台模型配置 fallback）
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, form.getModelConfigId());
        Map<String, String> extraEnvVars = resolveEnvVars(form.getModelConfigId(), directoryId, userId);

        // 5.5. 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        // 6. 发布任务启动事件 → WorkerStreamRelay 监听
        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), cwd, null, form.getModel(), form.getMaxTurns(), agentTeamsJson,
                form.getImages(),
                authParams[0], authParams[1], authParams[2], form.getPermissionMode(),
                navigatorApiKey, extraEnvVars));

        return toDTO(entity);
    }

    /**
     * 恢复任务（resume Claude Code 会话）
     */
    @Transactional
    public TaskDTO resumeTask(String userId, String tenantId, ResumeTaskForm form) {
        // Resume 必须指定 claudeSessionId 和 sessionId
        if (form.getClaudeSessionId() == null || form.getClaudeSessionId().isEmpty()) {
            throw new IllegalArgumentException("resume 操作必须指定 claudeSessionId");
        }
        if (form.getSessionId() == null || form.getSessionId().isEmpty()) {
            throw new IllegalArgumentException("resume 操作必须指定 sessionId");
        }

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }

        // 校验 claudeSessionId 在该 Worker 上是否有过历史任务记录
        if (!taskRepository.existsByClaudeSessionIdAndWorkerId(form.getClaudeSessionId(), form.getWorkerId())) {
            throw new IllegalArgumentException("Claude 会话不存在或不属于该 Worker: " + form.getClaudeSessionId());
        }

        // 并发保护：拒绝向正在运行任务的 Claude 会话发送新任务
        if (taskRepository.existsByClaudeSessionIdAndWorkerIdAndStatus(
                form.getClaudeSessionId(), form.getWorkerId(), "RUNNING")) {
            throw new IllegalStateException("该会话正在运行任务，请等待完成或终止后再继续");
        }

        // 如果指定了 directoryId，从目录解析 cwd 和 agentTeams
        String cwd = form.getCwd();
        String directoryId = form.getDirectoryId();
        String agentTeamsJson = form.getAgentTeamsJson();
        String resolvedConfigId = null;

        // 优先从会话级 ConversationConfig 读取已锁定的配置（创建后不可变更）
        ConversationConfigEntity existingConvConfig = conversationConfigService.getConfigEntity(form.getSessionId());
        if (existingConvConfig != null && existingConvConfig.getAgentTeamsConfigId() != null) {
            resolvedConfigId = existingConvConfig.getAgentTeamsConfigId();
            AgentTeamsConfigEntity lockedConfig = agentTeamsConfigService.getConfigEntity(resolvedConfigId);
            if (lockedConfig != null) {
                agentTeamsJson = lockedConfig.getConfig();
            }
        }

        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryService.getDirectoryEntity(userId, directoryId);
            if (!dir.getWorkerId().equals(form.getWorkerId())) {
                throw new IllegalArgumentException("Directory does not belong to the specified worker");
            }
            cwd = dir.getPath();
            // 仅在未锁定配置时才走解析链
            if (resolvedConfigId == null && (agentTeamsJson == null || agentTeamsJson.isEmpty())) {
                // 优先级 2: 表单指定的命名配置 ID
                if (form.getAgentTeamsConfigId() != null && !form.getAgentTeamsConfigId().isEmpty()) {
                    AgentTeamsConfigEntity config = agentTeamsConfigService.getConfigEntity(form.getAgentTeamsConfigId());
                    if (config != null && config.getDirectoryId().equals(directoryId)) {
                        agentTeamsJson = config.getConfig();
                        resolvedConfigId = config.getConfigId();
                    }
                }
                // 优先级 3: 目录默认命名配置
                if (agentTeamsJson == null || agentTeamsJson.isEmpty()) {
                    Optional<AgentTeamsConfigEntity> defaultConfig = agentTeamsConfigService.getDefaultConfig(directoryId, userId);
                    if (defaultConfig.isPresent()) {
                        agentTeamsJson = defaultConfig.get().getConfig();
                        resolvedConfigId = defaultConfig.get().getConfigId();
                    }
                }
                // 优先级 4: 旧 legacy 字段
                if (agentTeamsJson == null || agentTeamsJson.isEmpty()) {
                    if (dir.getAgentTeamsConfig() != null) {
                        agentTeamsJson = dir.getAgentTeamsConfig();
                    }
                }
            }
        }

        // 校验 sessionId 有效性
        Session existing = sessionManager.getSession(form.getSessionId());
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Session not found or access denied: " + form.getSessionId());
        }
        String sessionId = existing.getId();

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
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        entity.setAgentTeamsConfigId(resolvedConfigId);
        taskRepository.save(entity);

        log.info("Task resumed: taskId={}, claudeSessionId={}, directoryId={}", taskId, form.getClaudeSessionId(), directoryId);
        publishStatusChange(entity, null);
        conversationConfigService.updateInteractionState(sessionId, "PROCESSING");

        // 锁定 Agent Teams 配置到会话（仅首次，已锁定则跳过）
        if (resolvedConfigId != null && existingConvConfig != null && existingConvConfig.getAgentTeamsConfigId() == null) {
            existingConvConfig.setAgentTeamsConfigId(resolvedConfigId);
            conversationConfigRepository.save(existingConvConfig);
        }

        // 解析 per-conversation auth（含平台模型配置 fallback）
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, form.getModelConfigId());
        Map<String, String> extraEnvVars = resolveEnvVars(form.getModelConfigId(), directoryId, userId);

        // 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), cwd, form.getClaudeSessionId(),
                form.getModel(), form.getMaxTurns(), agentTeamsJson,
                form.getImages(), authParams[0], authParams[1], authParams[2], form.getPermissionMode(),
                navigatorApiKey, extraEnvVars));

        return toDTO(entity);
    }

    /**
     * 创建轻量 sync 任务记录（仅持久化 RUNNING 状态，不启动 SSE 流、不发布 ClaudeTaskStartEvent）
     * 用于 syncQueryTracked 场景：让 Workers 页"历史会话"面板能查到这些轻量查询。
     */
    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                         String prompt, String cwd, String directoryId,
                                         String claudeSessionId) {
        String taskId = IdGenerator.shortId();
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setPrompt(truncate(prompt, 200));
        entity.setCwd(cwd);
        entity.setDirectoryId(directoryId);
        entity.setClaudeSessionId(claudeSessionId);
        entity.setFileCheckpointingEnabled(false);
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        taskRepository.save(entity);
        log.info("Tracked sync task created: taskId={}, workerId={}, directoryId={}", taskId, workerId, directoryId);

        // 绑定目录 auth 到 session 的 ConversationConfig（UI 历史会话能看到 auth 状态）
        try {
            resolveAuth(sessionId, workerId, userId, directoryId, null);
        } catch (Exception e) {
            log.debug("Failed to bind auth for tracked sync task: {}", e.getMessage());
        }

        return taskId;
    }

    /**
     * 将同步查询的 prompt + result 持久化到 Session，使历史会话面板能显示对话内容
     */
    public void persistTrackedSyncMessages(String sessionId, String prompt, String resultText) {
        try {
            // USER prompt
            sessionManager.addMessage(sessionId, com.foggy.navigator.agent.framework.session.Message.builder()
                    .sessionId(sessionId)
                    .role(com.foggy.navigator.agent.framework.session.MessageRole.USER)
                    .content(prompt)
                    .build());
            // ASSISTANT result
            if (resultText != null && !resultText.isEmpty()) {
                sessionManager.addMessage(sessionId, com.foggy.navigator.agent.framework.session.Message.builder()
                        .sessionId(sessionId)
                        .role(com.foggy.navigator.agent.framework.session.MessageRole.ASSISTANT)
                        .content(resultText)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to persist tracked sync messages: sessionId={}, error={}", sessionId, e.getMessage());
        }
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
     * 列出用户所有进行中的任务（RUNNING + AWAITING_PERMISSION），额外填充 directoryName
     */
    public List<TaskDTO> listActiveTasks(String userId) {
        List<ClaudeTaskEntity> entities = taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of("RUNNING", "AWAITING_PERMISSION"));
        return entities.stream().map(e -> {
            TaskDTO dto = toDTO(e);
            if (e.getDirectoryId() != null) {
                workingDirectoryRepository.findByDirectoryId(e.getDirectoryId())
                        .ifPresent(dir -> dto.setDirectoryName(dir.getProjectName()));
            }
            return dto;
        }).toList();
    }

    /**
     * 列出用户所有待回复的任务（interactionState = AWAITING_REPLY），额外填充 directoryName
     */
    public List<TaskDTO> listAwaitingReplyTasks(String userId) {
        // 1. 从 ConversationConfig 查询所有 AWAITING_REPLY 状态的 sessionId
        List<String> awaitingSessionIds = conversationConfigRepository.findSessionIdsByInteractionState(
                userId, "AWAITING_REPLY");

        if (awaitingSessionIds.isEmpty()) {
            return List.of();
        }

        // 2. 查询这些 session 的最新任务（按 sessionId 分组，每组取最新的任务）
        List<ClaudeTaskEntity> latestTasks = taskRepository.findLatestBySessionIdIn(awaitingSessionIds);

        // 3. 转换为 DTO 并填充 directoryName
        return latestTasks.stream().map(e -> {
            TaskDTO dto = toDTO(e);
            if (e.getDirectoryId() != null) {
                workingDirectoryRepository.findByDirectoryId(e.getDirectoryId())
                        .ifPresent(dir -> dto.setDirectoryName(dir.getProjectName()));
            }
            return dto;
        }).toList();
    }

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
        return listTasksBySession(userId, page, size, null);
    }

    public SessionPageDTO listTasksBySession(String userId, int page, int size, String interactionState) {
        List<String> sessionIds;
        long totalSessions;

        if (interactionState != null && !interactionState.isEmpty()) {
            // 支持逗号分隔的多状态筛选（如 "AWAITING_REPLY,PROCESSING"）
            List<String> states = Arrays.stream(interactionState.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            List<String> allMatchingSessionIds;
            if (states.size() == 1) {
                allMatchingSessionIds =
                        conversationConfigService.findSessionIdsByInteractionState(userId, states.get(0));
            } else {
                allMatchingSessionIds =
                        conversationConfigService.findSessionIdsByInteractionStates(userId, states);
            }
            if (allMatchingSessionIds.isEmpty()) {
                return SessionPageDTO.builder()
                        .content(List.of()).totalSessions(0).page(page).size(size).build();
            }
            totalSessions = allMatchingSessionIds.size();
            // 对匹配的 sessionIds 按最新任务时间排序并分页
            List<String> sortedSessionIds = taskRepository.findDistinctSessionIdsByUserFilteredBySessionIds(
                    userId, allMatchingSessionIds, PageRequest.of(page, size));
            sessionIds = sortedSessionIds;
        } else {
            // 无过滤："全部"模式，显示所有会话（包括已归档）
            sessionIds = taskRepository.findDistinctSessionIdsByUser(userId, PageRequest.of(page, size));
            totalSessions = taskRepository.countDistinctSessionsByUser(userId);
        }

        if (sessionIds.isEmpty()) {
            return SessionPageDTO.builder()
                    .content(List.of()).totalSessions(totalSessions).page(page).size(size).build();
        }

        // 获取这些 session 的所有任务
        List<TaskDTO> tasks = taskRepository.findBySessionIdInAndUserIdOrderByCreatedAtDesc(sessionIds, userId)
                .stream().map(this::toDTO).toList();
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
        return listTasksByDirectorySession(userId, directoryId, page, size, null);
    }

    public SessionPageDTO listTasksByDirectorySession(String userId, String directoryId, int page, int size, String interactionState) {
        List<String> sessionIds;
        long totalSessions;

        if (interactionState != null && !interactionState.isEmpty()) {
            // 支持逗号分隔的多状态筛选
            List<String> states = Arrays.stream(interactionState.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            List<String> allMatchingSessionIds;
            if (states.size() == 1) {
                allMatchingSessionIds =
                        conversationConfigService.findSessionIdsByInteractionState(userId, states.get(0));
            } else {
                allMatchingSessionIds =
                        conversationConfigService.findSessionIdsByInteractionStates(userId, states);
            }
            if (allMatchingSessionIds.isEmpty()) {
                return SessionPageDTO.builder()
                        .content(List.of()).totalSessions(0).page(page).size(size).build();
            }
            sessionIds = taskRepository.findDistinctSessionIdsByDirectoryFilteredBySessionIds(
                    directoryId, userId, allMatchingSessionIds, PageRequest.of(page, size));
            // Count matching sessions within this directory
            totalSessions = taskRepository.countDistinctSessionsByDirectoryFilteredBySessionIds(
                    directoryId, userId, allMatchingSessionIds);
        } else {
            // 无过滤："全部"模式，显示所有会话（包括已归档）
            sessionIds = taskRepository.findDistinctSessionIdsByDirectory(directoryId, userId, PageRequest.of(page, size));
            totalSessions = taskRepository.countDistinctSessionsByDirectory(directoryId, userId);
        }

        if (sessionIds.isEmpty()) {
            return SessionPageDTO.builder()
                    .content(List.of()).totalSessions(totalSessions).page(page).size(size).build();
        }
        List<TaskDTO> tasks = taskRepository.findBySessionIdInAndUserIdOrderByCreatedAtDesc(sessionIds, userId)
                .stream().map(this::toDTO).toList();
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
            String prev = entity.getStatus();
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
            publishStatusChange(entity, prev);
            conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");
        });
    }

    /**
     * 标记任务失败（保留 claudeSessionId 以便后续继续会话）
     */
    @Transactional
    public void failTask(String taskId, String claudeSessionId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String prev = entity.getStatus();
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            if (claudeSessionId != null && entity.getClaudeSessionId() == null) {
                entity.setClaudeSessionId(claudeSessionId);
            }
            taskRepository.save(entity);
            log.warn("Task failed: taskId={}, claudeSessionId={}, error={}", taskId, claudeSessionId, errorMessage);
            publishStatusChange(entity, prev);
            conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");
        });
    }

    /**
     * 标记任务为等待权限审批
     */
    @Transactional
    public void setAwaitingPermission(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String prev = entity.getStatus();
            entity.setStatus("AWAITING_PERMISSION");
            taskRepository.save(entity);
            log.info("Task awaiting permission: taskId={}", taskId);
            publishStatusChange(entity, prev);
            conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");
        });
    }

    /**
     * 从等待权限恢复为运行中。
     *
     * @return true 如果成功将状态从 AWAITING_PERMISSION 更新为 RUNNING
     */
    @Transactional
    public boolean resumeFromPermission(String taskId, String permissionId, String decision,
                                         Map<String, String> answers) {
        var opt = taskRepository.findByTaskId(taskId);
        if (opt.isEmpty()) {
            log.warn("resumeFromPermission: task not found: taskId={}", taskId);
            return false;
        }
        ClaudeTaskEntity entity = opt.get();
        String currentStatus = entity.getStatus();
        if (!"AWAITING_PERMISSION".equals(currentStatus)) {
            log.warn("resumeFromPermission: task status is '{}', expected AWAITING_PERMISSION — "
                    + "forcing to RUNNING anyway (permission was already relayed to Worker): taskId={}",
                    currentStatus, taskId);
            // 即使状态不是 AWAITING_PERMISSION，也强制恢复为 RUNNING。
            // 因为 Worker 已经收到了 approve 响应，CLI 会继续执行。
            // 典型场景：SSE 竞态导致 status 被短暂改为其他值。
        }
        String prev = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null); // 清除之前可能的错误信息
        taskRepository.save(entity);
        log.info("Task resumed from permission: taskId={}, prev={}", taskId, prev);
        publishStatusChange(entity, prev);
        conversationConfigService.updateInteractionState(entity.getSessionId(), "PROCESSING");

        // Persist the user's response so it survives page refresh
        publishConfirmationResponse(entity.getSessionId(), taskId, permissionId, decision, answers);
        return true;
    }

    private void publishConfirmationResponse(String sessionId, String taskId, String permissionId,
                                              String decision, Map<String, String> answers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissionId", permissionId);
        payload.put("decision", decision);
        payload.put("taskId", taskId);
        if (answers != null && !answers.isEmpty()) {
            payload.put("answers", answers);
        }
        eventPublisher.publishEvent(
                AgentMessage.of(sessionId, AGENT_ID, MessageType.CONFIRMATION_RESPONSE, payload));
    }

    /**
     * 标记任务已中止
     */
    @Transactional
    public void abortTask(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String prev = entity.getStatus();
            entity.setStatus("ABORTED");
            taskRepository.save(entity);
            log.info("Task aborted: taskId={}", taskId);
            publishStatusChange(entity, prev);
            conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");
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

        // 记录被删除的 claudeSessionId，防止 syncLocalSessions 重新导入
        if (entity.getClaudeSessionId() != null && !entity.getClaudeSessionId().isEmpty()) {
            if (!deletedSessionRepository.existsByClaudeSessionIdAndWorkerIdAndUserId(
                    entity.getClaudeSessionId(), entity.getWorkerId(), userId)) {
                DeletedClaudeSessionEntity deleted = new DeletedClaudeSessionEntity();
                deleted.setClaudeSessionId(entity.getClaudeSessionId());
                deleted.setWorkerId(entity.getWorkerId());
                deleted.setUserId(userId);
                deletedSessionRepository.save(deleted);
            }
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

        // Batch load deleted session IDs to skip
        Set<String> deletedSessionIds = deletedSessionRepository.findByWorkerIdAndUserId(workerId, userId)
                .stream()
                .map(DeletedClaudeSessionEntity::getClaudeSessionId)
                .collect(Collectors.toSet());

        int created = 0;
        for (Map<String, Object> session : sessions) {
            String claudeSessionId = (String) session.get("session_id");
            if (claudeSessionId == null || claudeSessionId.isEmpty()) continue;

            // Skip sessions that were previously deleted by the user
            if (deletedSessionIds.contains(claudeSessionId)) {
                continue;
            }

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
            entity.setSource("SYNCED");
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
            // 校验 Worker 是否有权使用该模型
            llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
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
            if (dir != null) {
                // 优先使用目录绑定的平台 LLM 配置
                if (dir.getDefaultModelConfigId() != null && !dir.getDefaultModelConfigId().isEmpty()) {
                    // 校验 Worker 是否有权使用该模型（模型 scope 可能在绑定后变更）
                    llmModelManager.validateModelAccessForWorker(dir.getDefaultModelConfigId(), workerId);
                    LlmModelConfigDTO dirModelConfig = llmModelManager.getModelConfig(dir.getDefaultModelConfigId()).orElse(null);
                    if (dirModelConfig != null && Boolean.TRUE.equals(dirModelConfig.getHasApiKey())) {
                        String decryptedApiKey = llmModelManager.getDecryptedApiKey(dir.getDefaultModelConfigId());
                        log.info("Auth resolved from directory platform model config: {}", dirModelConfig.getName());
                        String authMode = (dirModelConfig.getBaseUrl() != null && !dirModelConfig.getBaseUrl().isEmpty())
                                ? "CUSTOM_ENDPOINT" : "API_KEY";
                        conversationConfigService.bindAuthFromDirectory(
                                sessionId, workerId, userId, authMode, decryptedApiKey, dirModelConfig.getBaseUrl());
                        return new String[]{decryptedApiKey, null, dirModelConfig.getBaseUrl()};
                    }
                }
                // 手动 auth 配置 fallback
                if (dir.getDefaultAuthMode() != null) {
                    String[] dirAuth = workingDirectoryService.getDecryptedDefaultAuth(dir);
                    conversationConfigService.bindAuthFromDirectory(
                            sessionId, workerId, userId, dirAuth[0], dirAuth[1], dirAuth[2]);
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
        }

        return new String[]{null, null, null};
    }

    /**
     * 从 LLM 模型配置中解析环境变量
     * 优先级：显式 modelConfigId → 目录默认 modelConfigId
     */
    private Map<String, String> resolveEnvVars(String modelConfigId, String directoryId, String userId) {
        // 优先使用显式指定的 modelConfigId
        if (modelConfigId != null && !modelConfigId.isEmpty()) {
            LlmModelConfigDTO config = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (config != null && config.getEnvVars() != null && !config.getEnvVars().isEmpty()) {
                return config.getEnvVars();
            }
        }
        // Fallback: 目录绑定的默认模型
        if (directoryId != null && !directoryId.isEmpty()) {
            WorkingDirectoryEntity dir = workingDirectoryRepository
                    .findByDirectoryIdAndUserId(directoryId, userId).orElse(null);
            if (dir != null && dir.getDefaultModelConfigId() != null) {
                LlmModelConfigDTO config = llmModelManager.getModelConfig(dir.getDefaultModelConfigId()).orElse(null);
                if (config != null && config.getEnvVars() != null && !config.getEnvVars().isEmpty()) {
                    return config.getEnvVars();
                }
            }
        }
        return null;
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
     * - 交互式任务（fileCheckpointingEnabled=true）：4 小时超时（与 Worker hard timeout 对齐）
     *   使用 lastAliveAt（Reconciler 心跳时间）作为基准，避免误判仍在运行的长任务
     * - Tracked sync 任务（fileCheckpointingEnabled=false）：10 分钟超时（syncQuery 正常 < 2 分钟）
     * - AWAITING_PERMISSION 任务：4 小时超时（同样使用 lastAliveAt 基准，用户可能长时间不在）
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void checkTimeoutTasks() {
        LocalDateTime now = LocalDateTime.now();
        int timedOutCount = 0;

        // 交互式编程任务：4 小时超时
        // 先用 createdAt 过滤出候选任务，再用 lastAliveAt（Reconciler 更新）作为真正基准，
        // 避免误杀 Reconciler 近期确认存活的任务。
        LocalDateTime runningCutoff = now.minusHours(4);
        List<ClaudeTaskEntity> runningOld = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", runningCutoff);
        for (ClaudeTaskEntity entity : runningOld) {
            if (Boolean.FALSE.equals(entity.getFileCheckpointingEnabled())) {
                continue; // sync tasks handled separately below
            }
            LocalDateTime baseline = entity.getLastAliveAt() != null ? entity.getLastAliveAt() : entity.getCreatedAt();
            if (baseline.isBefore(runningCutoff)) {
                failTimeout(entity, "Task timed out (exceeded 4 hours without CLI activity)");
                timedOutCount++;
            }
        }

        // Tracked sync tasks: 10 minute timeout (syncQuery should complete in < 2 min)
        // Sync tasks are short-lived, Reconciler does not track them — use createdAt directly.
        LocalDateTime syncCutoff = now.minusMinutes(10);
        List<ClaudeTaskEntity> syncOld = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", syncCutoff);
        for (ClaudeTaskEntity entity : syncOld) {
            if (Boolean.FALSE.equals(entity.getFileCheckpointingEnabled())) {
                failTimeout(entity, "Sync task timed out (exceeded 10 minutes)");
                timedOutCount++;
            }
        }

        // AWAITING_PERMISSION tasks: 4 hour timeout (aligned with interactive tasks).
        // Users may step away while a question/permission is pending — use a generous window.
        // Use lastAliveAt as baseline if Reconciler has confirmed the CLI is alive recently.
        LocalDateTime permissionCutoff = now.minusHours(4);
        List<ClaudeTaskEntity> permOld = taskRepository.findByStatusAndCreatedAtBefore("AWAITING_PERMISSION", permissionCutoff);
        for (ClaudeTaskEntity entity : permOld) {
            LocalDateTime baseline = entity.getLastAliveAt() != null ? entity.getLastAliveAt() : entity.getCreatedAt();
            if (baseline.isBefore(permissionCutoff)) {
                failTimeout(entity, "Permission request timed out (exceeded 4 hours without CLI activity)");
                timedOutCount++;
            }
        }

        if (timedOutCount > 0) {
            log.info("Marked {} timed-out tasks as FAILED", timedOutCount);
        }
    }

    /**
     * Reconciler 心跳：更新任务的最后存活时间，防止误判超时。
     * 每次 Reconciler 确认 CLI 进程仍在运行时调用。
     */
    @Transactional
    public void touchAlive(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setLastAliveAt(LocalDateTime.now());
            taskRepository.save(entity);
        });
    }

    /**
     * Reconciler 专用：按 taskId 强制将任务标记为 FAILED。
     * 仅对仍处于活跃状态（RUNNING / AWAITING_PERMISSION）的任务生效，幂等安全。
     */
    @Transactional
    public void reconcilerFailTask(String taskId, String reason) {
        reconcilerFailTask(taskId, reason, true);
    }

    /**
     * Reconciler 调用：标记任务为失败。
     *
     * @param taskId 任务 ID
     * @param reason 失败原因
     * @param shouldAbortWorker 是否调用 Worker 的 abortTask API（仅在确认 CLI 已死时为 true）
     */
    public void reconcilerFailTask(String taskId, String reason, boolean shouldAbortWorker) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String status = entity.getStatus();
            if ("RUNNING".equals(status) || "AWAITING_PERMISSION".equals(status)) {
                failTimeout(entity, reason, shouldAbortWorker);
            }
        });
    }

    /**
     * 流正常结束但任务仍在 RUNNING 时调用（未收到 result/error 事件）。
     * 典型场景：系统资源不足导致 CLI 无法启动，Worker 进程异常退出。
     * 标记任务失败 + 推送友好错误消息到会话。
     *
     * 注意：AWAITING_PERMISSION 任务不立即失败 — SSE 流在等待用户授权期间空闲易超时断开，
     * 但 CLI 进程通常仍然存活。由 TaskStateReconciler 定期检测 CLI 存活状态来处理。
     */
    @Transactional
    public void failIfStillRunning(String taskId, String sessionId, String reason) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String status = entity.getStatus();
            if ("AWAITING_PERMISSION".equals(status)) {
                // SSE 流断开但 CLI 可能仍在等待用户输入，不立即失败。
                // Reconciler 会检测 CLI 进程存活状态，真正死亡时会 reconcilerFailTask。
                log.info("SSE stream ended while task awaiting permission — deferring to Reconciler: taskId={}", taskId);
                return;
            }
            if ("RUNNING".equals(status)) {
                // 在标记 FAILED 之前，检查 CLI 进程是否仍然存活
                if (isCliProcessAlive(entity)) {
                    log.info("CLI process still alive — deferring to Reconciler: taskId={}", taskId);
                    return;
                }

                String prev = entity.getStatus();
                entity.setStatus("FAILED");
                entity.setErrorMessage(reason);
                taskRepository.save(entity);
                log.warn("Task stream ended without result: taskId={}, reason={}", taskId, reason);
                publishStatusChange(entity, prev);
                conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");
                publishSessionError(sessionId, taskId, reason, true);

                // 发布跨 Agent 任务失败事件
                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("FAILED")
                        .resultSummary(reason)
                        .build());
            }
        });
    }

    /**
     * 将 FAILED 任务重置为 RUNNING（用户手动重连场景）。
     */
    @Transactional
    public void resetToRunning(String taskId) {
        ClaudeTaskEntity entity = getTaskEntity(taskId);
        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be reconnected, current status: " + entity.getStatus());
        }
        String prev = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        taskRepository.save(entity);
        log.info("Task reset to RUNNING for reconnect: taskId={}", taskId);
        publishStatusChange(entity, prev);
    }

    /**
     * 重新同步已失败但 CLI 还在运行的任务。
     *
     * 使用场景：Reconciler 检测到 CLI 还活着但已标记为 FAILED，用户通过进程列表手动触发重新同步。
     *
     * 操作：
     * 1. 验证任务状态为 FAILED
     * 2. 查询 Worker 确认 CLI 还在运行（cli_alive=true）
     * 3. 将数据库状态改为 RUNNING
     * 4. 调用 streamRelay.reconnectTask() 复用消息对齐机制
     * 5. 发布 STATE_SYNC 事件通知前端
     *
     * @param taskId 任务 ID
     */
    @Transactional
    public void resyncTask(String taskId) {
        ClaudeTaskEntity entity = getTaskEntity(taskId);

        // 1. 检查：只有 FAILED 状态才能重新同步
        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be resynced, current status: " + entity.getStatus());
        }

        // 2. 检查：Worker 侧 CLI 必须还活着
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(entity.getWorkerId());
        Boolean cliAlive = false;
        try {
            Map<String, Object> status = workerService.createClient(worker)
                    .getTaskStatus(taskId)
                    .block(java.time.Duration.ofSeconds(5));
            if (status != null) {
                cliAlive = (Boolean) status.get("cli_alive");
            }
        } catch (Exception e) {
            log.warn("Failed to query Worker status for resync task {}: {}", taskId, e.getMessage());
        }

        if (Boolean.FALSE.equals(cliAlive)) {
            throw new IllegalStateException("CLI is already dead, cannot resync task: " + taskId);
        }

        // 3. 恢复任务状态
        String prev = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        taskRepository.save(entity);
        log.info("Task resynced: taskId={}, workerId={}", taskId, entity.getWorkerId());
        publishStatusChange(entity, prev);

        // 4. 触发重连拉取新事件（复用现有消息对齐机制）
        streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());

        // 5. 发布恢复通知到会话
        eventPublisher.publishEvent(
                AgentMessage.of(entity.getSessionId(), AGENT_ID, MessageType.STATE_SYNC,
                        Map.of("content", "Task resynced", "subtype", "resynced", "taskId", taskId)));
    }

    /**
     * 检查 Worker 上的 CLI 进程是否仍然存活。
     * 通过 Worker API 列出进程，匹配 foggy_task_id。
     * 异常时返回 false（保守策略：无法确认存活则允许标记失败）。
     */
    @SuppressWarnings("unchecked")
    private boolean isCliProcessAlive(ClaudeTaskEntity task) {
        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> processInfo = client.listCliProcesses()
                    .block(java.time.Duration.ofSeconds(5));
            if (processInfo == null) return false;

            // Worker returns { "processes": [ { "foggy_task_id": "...", ... }, ... ] }
            Object processesList = processInfo.get("processes");
            if (processesList instanceof java.util.List<?> processes) {
                return processes.stream()
                        .filter(p -> p instanceof Map)
                        .map(p -> (Map<String, Object>) p)
                        .anyMatch(p -> task.getTaskId().equals(p.get("foggy_task_id")));
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check CLI process alive for task {}: {}", task.getTaskId(), e.getMessage());
            return false;
        }
    }

    // ==================== Resync 任务重新同步 ====================

    /**
     * 任务重新同步主入口。
     * - CLI 仍活着 → 策略 A：重置状态为 RUNNING，重连 SSE
     * - CLI 已退出 → 策略 B：从 Worker JSONL 补齐消息
     *
     * 不标 @Transactional：包含大量远程 HTTP 调用（healthCheck / getTaskStatus /
     * listCliProcesses / getSessionMessages，最长可达 30s），避免长时间占用 DB 连接。
     * 各写操作通过 txTemplate 显式划定事务边界。
     */
    public ResyncResult resync(String taskId, String userId) {
        ClaudeTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be resynced, current status: " + entity.getStatus());
        }

        ResyncResult result = new ResyncResult();
        result.setTaskId(taskId);

        // 1. Worker 健康检查（网络 IO，事务外）
        ClaudeWorkerEntity worker;
        ClaudeWorkerClient client;
        try {
            worker = workerService.getWorkerEntity(entity.getWorkerId());
            client = workerService.createClient(worker);
            client.healthCheck().block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Resync: Worker unreachable for task {}: {}", taskId, e.getMessage());
            result.setAction("WORKER_UNREACHABLE");
            result.setCliStatus(CliStatus.unreachable(e.getMessage()));
            result.setTaskStatusAfter(entity.getStatus());
            return result;
        }

        // 2. 三层探测 CLI 状态（网络 IO，事务外）
        CliStatus cliStatus = detectCliStatus(client, entity);
        result.setCliStatus(cliStatus);

        if (cliStatus.isAlive()) {
            // 策略 A：CLI 存活 → 重连 SSE
            log.info("Resync: CLI alive for task {}, reconnecting SSE (Strategy A)", taskId);
            txTemplate.executeWithoutResult(status -> resetToRunning(taskId));
            try {
                streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
            } catch (Exception e) {
                log.warn("Resync: SSE reconnect failed for task {}: {}", taskId, e.getMessage());
            }
            result.setAction("RECONNECTED");
            result.setTaskStatusAfter("RUNNING");
        } else {
            // 策略 B：CLI 已退出 → 从 Worker JSONL 补齐消息
            log.info("Resync: CLI dead for task {}, syncing messages from Worker (Strategy B)", taskId);
            syncMessagesFromWorker(entity, client, result);
        }

        return result;
    }

    /**
     * 三层探测 CLI 进程存活状态。
     * 层1: task_registry / persistence (getTaskStatus)
     * 层2: 进程列表匹配 foggy_task_id
     * 层3: 都失败 → alive=false
     */
    @SuppressWarnings("unchecked")
    private CliStatus detectCliStatus(ClaudeWorkerClient client, ClaudeTaskEntity task) {
        String taskId = task.getTaskId();

        // 层1: 查询 Worker task_registry
        try {
            Map<String, Object> status = client.getTaskStatus(taskId).block(Duration.ofSeconds(5));
            if (status != null) {
                Boolean cliAlive = (Boolean) status.get("cli_alive");
                Boolean closed = (Boolean) status.get("closed");
                String source = (String) status.get("source");

                if (Boolean.TRUE.equals(cliAlive)) {
                    return CliStatus.builder()
                            .alive(true).workerReachable(true).taskInRegistry(true)
                            .source("task_status").detail("CLI alive via " + source)
                            .build();
                }
                if (Boolean.TRUE.equals(closed)) {
                    return CliStatus.builder()
                            .alive(false).workerReachable(true).taskInRegistry(true)
                            .source("task_status").detail("Task closed via " + source)
                            .build();
                }
                // 有记录但 cli_alive 不确定，继续层2
            }
        } catch (Exception e) {
            log.debug("Resync: Layer1 task_status check failed for task {}: {}", taskId, e.getMessage());
        }

        // 层2: 进程列表匹配
        try {
            Map<String, Object> processInfo = client.listCliProcesses().block(Duration.ofSeconds(5));
            if (processInfo != null) {
                Object processesList = processInfo.get("processes");
                if (processesList instanceof List<?> processes) {
                    boolean found = processes.stream()
                            .filter(p -> p instanceof Map)
                            .map(p -> (Map<String, Object>) p)
                            .anyMatch(p -> taskId.equals(p.get("foggy_task_id")));
                    if (found) {
                        return CliStatus.builder()
                                .alive(true).workerReachable(true).taskInRegistry(false)
                                .source("process_list").detail("CLI process found in process list")
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Resync: Layer2 process_list check failed for task {}: {}", taskId, e.getMessage());
        }

        // 层3: 兜底 — 都无法确认存活
        return CliStatus.builder()
                .alive(false).workerReachable(true).taskInRegistry(false)
                .source("fallback").detail("CLI not found in task_registry or process list")
                .build();
    }

    /**
     * 策略 B：从 Worker JSONL 补齐平台侧缺失的消息。
     */
    @SuppressWarnings("unchecked")
    private void syncMessagesFromWorker(ClaudeTaskEntity task, ClaudeWorkerClient client, ResyncResult result) {
        String claudeSessionId = task.getClaudeSessionId();
        String sessionId = task.getSessionId();
        String taskId = task.getTaskId();

        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            log.warn("Resync: No claudeSessionId for task {}, cannot sync messages", taskId);
            result.setAction("NO_SESSION_DATA");
            result.setTaskStatusAfter(task.getStatus());
            return;
        }

        // 获取 Worker 侧消息
        List<Map<String, Object>> workerMessages;
        try {
            workerMessages = client.getSessionMessages(claudeSessionId).block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.warn("Resync: Failed to get Worker messages for session {}: {}", claudeSessionId, e.getMessage());
            result.setAction("NO_SESSION_DATA");
            result.setTaskStatusAfter(task.getStatus());
            return;
        }
        if (workerMessages == null || workerMessages.isEmpty()) {
            log.info("Resync: No messages found on Worker for session {}", claudeSessionId);
            result.setAction("NO_SESSION_DATA");
            result.setTaskStatusAfter(task.getStatus());
            return;
        }

        // 获取平台侧消息
        List<Message> platformMessages = sessionManager.getAllMessages(sessionId);

        // 构建同步报告
        MessageSyncReport report = new MessageSyncReport();
        report.setPlatformBefore(countMessages(platformMessages));
        report.setWorkerTotal(countWorkerMessages(workerMessages));

        // 计算缺失消息
        List<Map<String, Object>> missing = computeMissing(platformMessages, workerMessages);

        if (missing.isEmpty()) {
            log.info("Resync: Messages already aligned for task {}", taskId);
            result.setAction("ALREADY_ALIGNED");
            report.setImported(0);
            report.setPlatformAfter(report.getPlatformBefore());
            report.setMissingPreview(List.of());
            result.setMessageSync(report);
            // 即使消息已对齐，仍将 FAILED 任务标记为 COMPLETED
            txTemplate.executeWithoutResult(status -> markAsCompletedFromSync(task));
            result.setTaskStatusAfter("COMPLETED");
            return;
        }

        // 构建预览（最多10条，content 截断200字）— 纯计算，事务外
        List<Map<String, Object>> preview = missing.stream()
                .limit(10)
                .map(m -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("role", m.get("role"));
                    String content = m.get("content") != null ? m.get("content").toString() : "";
                    p.put("content", content.length() > 200 ? content.substring(0, 200) + "..." : content);
                    return p;
                })
                .collect(Collectors.toList());
        report.setMissingPreview(preview);

        // 导入缺失消息 + 标记完成 — 在同一个事务内，保证原子性
        txTemplate.executeWithoutResult(status -> {
            importMessages(sessionId, missing);
            markAsCompletedFromSync(task);
        });
        report.setImported(missing.size());

        // 同步后重新计数（事务已提交，读到最新数据）
        List<Message> platformAfter = sessionManager.getAllMessages(sessionId);
        report.setPlatformAfter(countMessages(platformAfter));

        result.setAction("MESSAGES_SYNCED");
        result.setMessageSync(report);
        result.setTaskStatusAfter("COMPLETED");
        log.info("Resync: Synced {} messages for task {}", missing.size(), taskId);
    }

    /**
     * 有序指纹匹配：找出 Worker 中有而平台中缺失的消息。
     * 指纹 = role:content前200字符(strip后)
     * 顺序匹配而非集合差集，正确处理重复内容（如多次"继续"）。
     */
    private List<Map<String, Object>> computeMissing(List<Message> platformMessages, List<Map<String, Object>> workerMessages) {
        // 构建平台消息指纹列表（保持顺序）
        List<String> platformFingerprints = platformMessages.stream()
                .map(m -> fingerprint(m.getRole() != null ? m.getRole().name().toLowerCase() : "unknown", m.getContent()))
                .collect(Collectors.toList());

        List<Map<String, Object>> missing = new ArrayList<>();
        int platformIdx = 0;

        for (Map<String, Object> wm : workerMessages) {
            String role = wm.get("role") != null ? wm.get("role").toString() : "unknown";
            String content = wm.get("content") != null ? wm.get("content").toString() : "";
            String wFingerprint = fingerprint(role, content);

            // 尝试在平台指纹列表中从当前位置开始匹配
            boolean matched = false;
            while (platformIdx < platformFingerprints.size()) {
                if (platformFingerprints.get(platformIdx).equals(wFingerprint)) {
                    platformIdx++;
                    matched = true;
                    break;
                }
                // 平台有多余消息（如 ERROR/STATE_SYNC），跳过
                platformIdx++;
            }

            if (!matched) {
                missing.add(wm);
            }
        }

        return missing;
    }

    private String fingerprint(String role, String content) {
        String trimmed = content != null ? content.strip() : "";
        String truncated = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        return role + ":" + truncated;
    }

    /**
     * 将缺失消息导入到平台会话中。
     */
    private void importMessages(String sessionId, List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            String role = m.get("role") != null ? m.get("role").toString() : "assistant";
            String content = m.get("content") != null ? m.get("content").toString() : "";
            MessageRole messageRole = "user".equalsIgnoreCase(role) ? MessageRole.USER : MessageRole.ASSISTANT;

            Message message = Message.builder()
                    .sessionId(sessionId)
                    .role(messageRole)
                    .content(content)
                    .build();

            // 保留 Worker 侧的原始时间戳
            if (m.get("timestamp") != null) {
                try {
                    message.setCreatedAt(java.time.LocalDateTime.parse(m.get("timestamp").toString()));
                } catch (Exception e) {
                    // 解析失败则使用当前时间
                    log.debug("Resync: Failed to parse timestamp '{}', using now", m.get("timestamp"));
                }
            }

            sessionManager.addMessage(sessionId, message);
        }
    }

    /**
     * 将 FAILED 任务标记为 COMPLETED（策略 B 同步后）。
     */
    private void markAsCompletedFromSync(ClaudeTaskEntity task) {
        String prev = task.getStatus();
        task.setStatus("COMPLETED");
        task.setErrorMessage(null);
        taskRepository.save(task);
        log.info("Resync: Task marked as COMPLETED from sync: taskId={}", task.getTaskId());
        publishStatusChange(task, prev);
        conversationConfigService.updateInteractionState(task.getSessionId(), "AWAITING_REPLY");
    }

    private MessageCount countMessages(List<Message> messages) {
        int user = (int) messages.stream().filter(m -> m.getRole() == MessageRole.USER).count();
        int assistant = (int) messages.stream().filter(m -> m.getRole() == MessageRole.ASSISTANT).count();
        return new MessageCount(user, assistant, messages.size());
    }

    private MessageCount countWorkerMessages(List<Map<String, Object>> messages) {
        int user = (int) messages.stream().filter(m -> "user".equals(m.get("role"))).count();
        int assistant = (int) messages.stream().filter(m -> "assistant".equals(m.get("role"))).count();
        return new MessageCount(user, assistant, messages.size());
    }

    // ==================== End Resync ====================

    private void failTimeout(ClaudeTaskEntity entity, String reason) {
        failTimeout(entity, reason, true);
    }

    /**
     * 标记任务为失败。
     *
     * @param entity 任务实体
     * @param reason 失败原因
     * @param shouldAbortWorker 是否调用 Worker 的 abortTask API（仅在确认 CLI 已死时为 true）
     */
    private void failTimeout(ClaudeTaskEntity entity, String reason, boolean shouldAbortWorker) {
        String prev = entity.getStatus();
        entity.setStatus("FAILED");
        entity.setErrorMessage(reason);
        taskRepository.save(entity);
        log.warn("Task timed out: taskId={}, createdAt={}, reason={}, shouldAbortWorker={}",
                entity.getTaskId(), entity.getCreatedAt(), reason, shouldAbortWorker);
        publishStatusChange(entity, prev);
        conversationConfigService.updateInteractionState(entity.getSessionId(), "AWAITING_REPLY");

        String taskId = entity.getTaskId();

        // 推送错误消息到会话，让用户在聊天中看到失败原因
        publishSessionError(entity.getSessionId(), taskId, reason, false);

        // 仅在确认 CLI 已死时通知 Worker 中止任务
        if (shouldAbortWorker) {
            try {
                ClaudeWorkerEntity worker = workerService.getWorkerEntity(entity.getWorkerId());
                ClaudeWorkerClient client = workerService.createClient(worker);
                client.abortTask(taskId).block(Duration.ofSeconds(3));
                log.info("Worker abort sent on timeout: taskId={}", taskId);
            } catch (Exception e) {
                log.warn("Failed to abort worker task on timeout: taskId={}, error={}", taskId, e.getMessage());
            }
        } else {
            log.info("CLI still alive, skipping Worker abort for task={}: user can decide via process list", taskId);
        }

        // 清理本地 SSE 流订阅
        try {
            streamRelay.abortStream(taskId);
        } catch (Exception e) {
            log.warn("Failed to abort local stream on timeout: taskId={}", taskId, e.getMessage());
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

    /**
     * 推送 ERROR 消息到会话，让用户在聊天界面中看到错误原因。
     * 仅用于显示，不影响 LLM 上下文（Claude Worker 的 LLM 上下文在远端 CLI 上）。
     */
    private void publishSessionError(String sessionId, String taskId, String errorMessage, boolean reconnectable) {
        if (sessionId == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", errorMessage);
            payload.put("taskId", taskId);
            payload.put("reconnectable", reconnectable);
            eventPublisher.publishEvent(
                    AgentMessage.of(sessionId, AGENT_ID, MessageType.ERROR, payload));
        } catch (Exception e) {
            log.warn("Failed to publish session error: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void publishStatusChange(ClaudeTaskEntity entity, String previousStatus) {
        String interactionState = deriveInteractionState(entity.getStatus());
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(AGENT_ID)
                .status(entity.getStatus())
                .previousStatus(previousStatus)
                .errorMessage(entity.getErrorMessage())
                .interactionState(interactionState)
                .build());
    }

    private String deriveInteractionState(String taskStatus) {
        if ("RUNNING".equals(taskStatus)) return "PROCESSING";
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus)
                || "AWAITING_PERMISSION".equals(taskStatus) || "ABORTED".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
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
                .source(entity.getSource())
                .agentTeamsConfigId(entity.getAgentTeamsConfigId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

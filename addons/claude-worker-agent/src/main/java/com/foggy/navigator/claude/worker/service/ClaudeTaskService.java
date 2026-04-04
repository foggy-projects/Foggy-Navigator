package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.foggy.navigator.claude.worker.model.dto.SessionSearchResultDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
public class ClaudeTaskService implements TaskQueryProvider {

    private static final String AGENT_ID = "claude-worker";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ClaudeTaskRepository taskRepository;
    private final ClaudeWorkerService workerService;
    private final AgentTeamsConfigService agentTeamsConfigService;
    private final CodingAgentRepository codingAgentRepository;

    /** @Lazy 打破 ClaudeTaskService ↔ WorkerStreamRelay 的循环依赖 */
    @Autowired @Lazy
    private WorkerStreamRelay streamRelay;

    @Autowired(required = false)
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    private SessionEntityRepository sessionEntityRepository;

    private final WorkingDirectoryService workingDirectoryService;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final LlmModelManager llmModelManager;
    private final UserAuthService userAuthService;
    private final CredentialEncryptor credentialEncryptor;
    private final TransactionTemplate txTemplate;

    @Value("${navigator.api.external-url:http://localhost:${server.port:8112}}")
    private String navigatorApiBase;

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

        String logicalAgentId = resolveLogicalAgentId(form.getAgentId(), null);

        // 3. 创建或复用 Foggy Session
        String sessionId = form.getSessionId();
        if (sessionId != null && sessionManager.getSession(sessionId) != null) {
            log.info("Reusing session {} from form.sessionId", sessionId);
        } else {
            sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .agentId(logicalAgentId)
                    .providerType(AGENT_ID)
                    .taskName(truncate(form.getPrompt(), 100))
                    .build());
        }

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
        entity.setResolvedAgentId(logicalAgentId);
        entity.setFileCheckpointingEnabled(true);
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        entity.setAgentTeamsConfigId(resolvedConfigId);
        entity.setContextId(form.getContextId());
        persistTask(entity);

        log.info("Task created: taskId={}, sessionId={}, workerId={}, userId={}, agentTeams={}",
                taskId, sessionId, form.getWorkerId(), userId,
                agentTeamsJson != null ? "enabled(" + agentTeamsJson.length() + " chars)" : "disabled");
        publishStatusChange(entity, null);
        updateSessionInteractionState(sessionId, "PROCESSING");

        // 4.5. 锁定 Agent Teams 配置到会话
        if (resolvedConfigId != null) {
            lockAgentTeamsConfigToSession(sessionId, form.getWorkerId(), userId, resolvedConfigId);
        }

        // 5. 解析 per-conversation auth（含平台模型配置 fallback）
        // 优先级：显式指定 > AgentModelOverride > Agent.defaultModelConfigId > Directory 默认
        String effectiveModelConfigId = resolveEffectiveModelConfigId(
                form.getModelConfigId(), logicalAgentId, tenantId);
        // 持久化使用的模型配置 ID，便于前端恢复会话模型选择
        if (effectiveModelConfigId != null && !effectiveModelConfigId.isEmpty()) {
            entity.setModelConfigId(effectiveModelConfigId);
            taskRepository.save(entity);
        }
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, effectiveModelConfigId);
        Map<String, String> extraEnvVars = resolveEnvVars(effectiveModelConfigId, directoryId, userId);

        // 5.5. 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        // 6. 发布任务启动事件 → WorkerStreamRelay 监听
        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .userId(userId).prompt(form.getPrompt()).cwd(cwd)
                .model(form.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(authParams[0]).providerType(AGENT_ID)
                .providerConfig(Map.of(
                        "claudeSessionId", form.getClaudeSessionId() != null ? form.getClaudeSessionId() : "",
                        "agentTeamsJson", agentTeamsJson != null ? agentTeamsJson : "",
                        "images", form.getImages() != null ? form.getImages() : "",
                        "authToken", authParams[1] != null ? authParams[1] : "",
                        "baseUrl", authParams[2] != null ? authParams[2] : "",
                        "permissionMode", form.getPermissionMode() != null ? form.getPermissionMode() : "",
                        "navigatorApiKey", navigatorApiKey != null ? navigatorApiKey : "",
                        "navigatorApiBase", navigatorApiBase != null ? navigatorApiBase : "",
                        "extraEnvVars", extraEnvVars != null ? (Object) extraEnvVars : ""
                )).build());

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

        String logicalAgentId = resolveLogicalAgentId(form.getAgentId(), form.getSessionId());

        // 如果指定了 directoryId，从目录解析 cwd 和 agentTeams
        String cwd = form.getCwd();
        String directoryId = form.getDirectoryId();
        String agentTeamsJson = form.getAgentTeamsJson();
        String resolvedConfigId = null;

        // 优先从会话级 SessionEntity.providerStateJson 读取已锁定的配置（创建后不可变更）
        String existingAgentTeamsConfigId = readAgentTeamsConfigId(form.getSessionId());
        if (existingAgentTeamsConfigId != null) {
            resolvedConfigId = existingAgentTeamsConfigId;
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
        entity.setResolvedAgentId(logicalAgentId);
        entity.setClaudeSessionId(form.getClaudeSessionId());
        entity.setFileCheckpointingEnabled(true);
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        entity.setAgentTeamsConfigId(resolvedConfigId);
        persistTask(entity);

        log.info("Task resumed: taskId={}, claudeSessionId={}, directoryId={}, agentTeams={}",
                taskId, form.getClaudeSessionId(), directoryId,
                agentTeamsJson != null ? "enabled(" + agentTeamsJson.length() + " chars)" : "disabled");
        publishStatusChange(entity, null);
        updateSessionInteractionState(sessionId, "PROCESSING");

        // 锁定 Agent Teams 配置到会话（仅首次，已锁定则跳过）
        if (resolvedConfigId != null && existingAgentTeamsConfigId == null) {
            lockAgentTeamsConfigToSession(sessionId, form.getWorkerId(), userId, resolvedConfigId);
        }

        // 解析 per-conversation auth（含平台模型配置 fallback）
        String modelConfigId = form.getModelConfigId();
        // 持久化使用的模型配置 ID，便于前端恢复会话模型选择
        if (modelConfigId != null && !modelConfigId.isEmpty()) {
            entity.setModelConfigId(modelConfigId);
            taskRepository.save(entity);
        }
        String[] authParams = resolveAuth(sessionId, form.getWorkerId(), userId, directoryId, modelConfigId);
        Map<String, String> extraEnvVars = resolveEnvVars(modelConfigId, directoryId, userId);

        // 生成内部服务 Token（用于 CLI 子进程回调 Navigator API）
        String navigatorApiKey = userAuthService.generateServiceToken(userId);

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .userId(userId).prompt(form.getPrompt()).cwd(cwd)
                .model(form.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(authParams[0]).providerType(AGENT_ID)
                .providerConfig(Map.of(
                        "claudeSessionId", form.getClaudeSessionId() != null ? form.getClaudeSessionId() : "",
                        "agentTeamsJson", agentTeamsJson != null ? agentTeamsJson : "",
                        "images", form.getImages() != null ? form.getImages() : "",
                        "authToken", authParams[1] != null ? authParams[1] : "",
                        "baseUrl", authParams[2] != null ? authParams[2] : "",
                        "permissionMode", form.getPermissionMode() != null ? form.getPermissionMode() : "",
                        "navigatorApiKey", navigatorApiKey != null ? navigatorApiKey : "",
                        "navigatorApiBase", navigatorApiBase != null ? navigatorApiBase : "",
                        "extraEnvVars", extraEnvVars != null ? (Object) extraEnvVars : ""
                )).build());

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
        return createTrackedSyncTask(userId, workerId, sessionId, prompt, cwd,
                directoryId, claudeSessionId, null);
    }

    /**
     * 创建轻量 sync 任务记录（带 contextId 支持 A2A 多轮会话）
     */
    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                         String prompt, String cwd, String directoryId,
                                         String claudeSessionId, String contextId) {
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
        entity.setContextId(contextId);
        entity.setFileCheckpointingEnabled(false);
        entity.setSource("PLATFORM");
        entity.setStatus("RUNNING");
        persistTask(entity);
        log.info("Tracked sync task created: taskId={}, workerId={}, directoryId={}", taskId, workerId, directoryId);

        // 绑定目录 auth 到 SessionEntity（UI 历史会话能看到 auth 状态）
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
        // 1. 从 SessionEntity 查询所有 AWAITING_REPLY 状态的 sessionId
        List<String> awaitingSessionIds = findSessionIdsByInteractionStateDirect(
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
                        findSessionIdsByInteractionStateDirect(userId, states.get(0));
            } else {
                allMatchingSessionIds =
                        findSessionIdsByInteractionStatesDirect(userId, states);
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
     * 按目录列出任务（内部 DTO）
     * @deprecated 前端迁移到统一 API 后，改用 SPI 方法 {@link #listTasksByDirectory(String, String)}
     */
    @Deprecated
    public List<TaskDTO> listTasksByDirectoryDTO(String userId, String directoryId) {
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
                        findSessionIdsByInteractionStateDirect(userId, states.get(0));
            } else {
                allMatchingSessionIds =
                        findSessionIdsByInteractionStatesDirect(userId, states);
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
            persistTask(entity);
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
            persistTask(entity);
            log.debug("Checkpoint added: taskId={}, checkpointId={}, total={}", taskId, checkpointId, list.size());
        });
    }

    /**
     * 更新任务状态为完成
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String claudeSessionId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String prev = entity.getStatus();
            entity.setStatus("COMPLETED");
            if (workerTaskId != null && !workerTaskId.isBlank()) {
                entity.setWorkerTaskId(workerTaskId);
            }
            if (claudeSessionId != null && !claudeSessionId.isBlank()) {
                entity.setClaudeSessionId(claudeSessionId);
            }
            if (resultText != null) {
                entity.setResultText(resultText);
            }
            if (costUsd != null) {
                entity.setCostUsd(costUsd);
            }
            if (inputTokens != null) {
                entity.setInputTokens(inputTokens);
            }
            if (outputTokens != null) {
                entity.setOutputTokens(outputTokens);
            }
            if (durationMs != null) {
                entity.setDurationMs(durationMs);
            }
            if (numTurns != null) {
                entity.setNumTurns(numTurns);
            }
            if (model != null) {
                entity.setModel(model);
            }
            entity.setErrorMessage(null);
            entity.setLastAliveAt(LocalDateTime.now());
            persistTask(entity);
            log.info("Task completed: taskId={}, model={}, costUsd={}, durationMs={}", taskId, model, costUsd, durationMs);
            publishStatusChange(entity, prev);
            updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY");
        });
    }

    /**
     * 幂等安全地更新任务的 claudeSessionId
     * 仅在字段为 null 或值不同时更新，避免不必要的数据库写入
     */
    @Transactional
    public void updateClaudeSessionId(String taskId, String claudeSessionId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            if (entity.getClaudeSessionId() == null || !entity.getClaudeSessionId().equals(claudeSessionId)) {
                entity.setClaudeSessionId(claudeSessionId);
                persistTask(entity);
                log.debug("Updated claudeSessionId for task {}: {}", taskId, claudeSessionId);
            }
        });
    }

    /**
     * 记录 Worker 侧任务标识、会话信息和事件消费进度。
     */
    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String claudeSessionId,
                                      String model, Integer ackSeq) {
        ClaudeTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("recordWorkerProgress: task not found: {}", taskId);
            return;
        }

        if (workerTaskId != null && !workerTaskId.isBlank()) {
            entity.setWorkerTaskId(workerTaskId);
        }
        if (claudeSessionId != null && !claudeSessionId.isBlank()) {
            entity.setClaudeSessionId(claudeSessionId);
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
     * A2A 幂等：根据 dedupKey 查找近期重复任务
     *
     * @param dedupKey      去重键 (hash of userId + agentId + prompt)
     * @param windowSeconds 时间窗口（秒），只查找此时间段内的任务
     * @return 如果存在近期重复任务则返回对应 TaskDTO
     */
    @Transactional(readOnly = true)
    public Optional<TaskDTO> findRecentByDedupKey(String dedupKey, int windowSeconds) {
        if (dedupKey == null) return Optional.empty();
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(windowSeconds);
        return taskRepository.findFirstByDedupKeyAndCreatedAtAfterOrderByCreatedAtDesc(dedupKey, cutoff)
                .map(this::toDTO);
    }

    /**
     * 设置任务的 dedupKey（任务创建后补充设置）
     */
    @Transactional
    public void setDedupKey(String taskId, String dedupKey) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setDedupKey(dedupKey);
            persistTask(entity);
        });
    }

    /**
     * 更新任务来源标记（如 "A2A" 表示 A2A 异步任务，不需要 WorkerStreamRelay 监控）
     */
    @Transactional
    public void setSource(String taskId, String source) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setSource(source);
            persistTask(entity);
        });
    }

    /**
     * 保存异步任务的结果文本（A2A 轮询 getTask 时返回 artifacts）
     */
    @Transactional
    public void saveTaskResult(String taskId, String resultText) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setResultText(resultText);
            persistTask(entity);
            log.debug("Saved result text for task {}: length={}", taskId,
                    resultText != null ? resultText.length() : 0);
        });
    }

    /**
     * 标记任务失败（保留 claudeSessionId 以便后续继续会话）
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String claudeSessionId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String prev = entity.getStatus();
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            if (workerTaskId != null && !workerTaskId.isBlank()) {
                entity.setWorkerTaskId(workerTaskId);
            }
            if (claudeSessionId != null && !claudeSessionId.isBlank()) {
                entity.setClaudeSessionId(claudeSessionId);
            }
            entity.setLastAliveAt(LocalDateTime.now());
            persistTask(entity);
            log.warn("Task failed: taskId={}, claudeSessionId={}, error={}", taskId, claudeSessionId, errorMessage);
            publishStatusChange(entity, prev);
            updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY");
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
            persistTask(entity);
            log.info("Task awaiting permission: taskId={}", taskId);
            publishStatusChange(entity, prev);
            updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY");
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
        persistTask(entity);
        log.info("Task resumed from permission: taskId={}, prev={}", taskId, prev);
        publishStatusChange(entity, prev);
        updateSessionInteractionState(entity.getSessionId(), "PROCESSING");

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
     * 保留 claudeSessionId 以便用户可以继续该会话，并通知 Worker 中止任务
     */
    /**
     * 中止任务 — 统一入口（Controller 和 A2aAgent 共用）。
     *
     * 执行顺序：
     * 1. 获取 Worker 内部 task ID（精确映射，必须在 abortStream 之前）
     * 2. 通知 Worker 中止 CLI 进程（SIGTERM）
     * 3. 清理本地 SSE 流订阅
     * 4. 更新 DB 状态 → ABORTED
     */
    @SuppressWarnings("unchecked")
    /**
     * 检查指定 Claude 会话是否有正在运行的任务（并发保护）
     */
    public boolean hasRunningTask(String claudeSessionId, String workerId) {
        return taskRepository.existsByClaudeSessionIdAndWorkerIdAndStatus(claudeSessionId, workerId, "RUNNING");
    }

    public void abortTask(String taskId) {
        // Terminal-state guard（Claude 原先缺失，现统一补上）
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task != null && ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())
                || "ABORTED".equals(task.getStatus()))) {
            log.info("Task {} already in terminal state ({}), skipping abort", taskId, task.getStatus());
            return;
        }

        String remoteId = resolveWorkerTaskLookupId(task);
        doAbortWorkerTask(taskId, remoteId);
        doPostAbort(taskId);
    }

    /**
     * 远端中止 + 流清理 + 状态落库 + 事件发布。
     * <p>
     * 由 {@code ClaudeWorkerInnerA2aAgent.abortWorkerTask()} 和 {@code abortTask()} 复用。
     * 不包含 Provider 专属后置钩子。
     *
     * @param taskId       平台侧 taskId
     * @param remoteTaskId 已解析的远端 Worker 任务标识（可能为 null）
     */
    public void doAbortWorkerTask(String taskId, String remoteTaskId) {
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);

        // 1. 通知 Worker 中止 CLI（在清理流之前，确保 Worker task 还在 registry）
        try {
            if (task != null) {
                ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
                ClaudeWorkerClient client = workerService.createClient(worker);

                if (remoteTaskId != null && !remoteTaskId.isBlank()) {
                    client.abortTask(remoteTaskId).block(Duration.ofSeconds(5));
                    log.info("Worker abort sent: taskId={}, remoteTaskId={}", taskId, remoteTaskId);
                } else if ("A2A".equals(task.getSource())) {
                    // A2A fallback：异步任务没有 SSE 流，通过进程列表匹配 PID → SIGTERM
                    killCliByTaskId(client, taskId);
                } else {
                    log.warn("Cannot abort task {}: no remoteTaskId and not A2A", taskId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to notify worker for abort task {}: {}", taskId, e.getMessage());
        }

        // 2. 清理本地 SSE 流订阅（停止重连、dispose Flux、清除映射）
        streamRelay.abortStream(taskId);

        // 3. 更新 DB 状态 → ABORTED（单独事务，避免网络 IO 阻塞事务）
        txTemplate.executeWithoutResult(status -> {
            taskRepository.findByTaskId(taskId).ifPresent(entity -> {
                String prev = entity.getStatus();
                entity.setStatus("ABORTED");
                persistTask(entity);
                log.info("Task aborted: taskId={}", taskId);
                publishStatusChange(entity, prev);
            });
        });
    }

    /**
     * Claude 专属 abort 后置钩子：更新 session interaction state + 异步扫描 checkpoints。
     * <p>
     * 由 {@code ClaudeWorkerInnerA2aAgent.onPostAbort()} 和 {@code abortTask()} 复用。
     *
     * @param taskId 平台侧 taskId
     */
    public void doPostAbort(String taskId) {
        // 更新 session interaction state → AWAITING_REPLY
        taskRepository.findByTaskId(taskId).ifPresent(entity ->
                updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY")
        );

        // 异步扫描 JSONL 补齐 checkpoints（中止的任务也可能已有有效 checkpoint）
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String claudeSessionId = entity.getClaudeSessionId();
            if (claudeSessionId != null && !claudeSessionId.isEmpty()) {
                streamRelay.autoScanCheckpoints(taskId, claudeSessionId);
            }
        });
    }

    /**
     * A2A 任务 abort fallback：通过 Worker 进程列表匹配 foggy_task_id 找到 PID → SIGTERM
     * <p>
     * A2A 异步任务不走 SSE 流，没有 workerTaskId 映射。
     * 需要通过 Worker API 列出所有 CLI 进程，按 foggy_task_id 匹配后杀进程。
     */
    @SuppressWarnings("unchecked")
    private void killCliByTaskId(ClaudeWorkerClient client, String taskId) {
        try {
            Map<String, Object> processInfo = client.listCliProcesses()
                    .block(Duration.ofSeconds(5));
            if (processInfo == null) return;

            Object processesList = processInfo.get("processes");
            if (!(processesList instanceof java.util.List<?> processes)) return;

            for (Object item : processes) {
                if (!(item instanceof Map<?, ?> proc)) continue;
                if (taskId.equals(proc.get("foggy_task_id"))) {
                    Object pidObj = proc.get("pid");
                    if (pidObj instanceof Number) {
                        int pid = ((Number) pidObj).intValue();
                        client.killCliProcess(pid, false).block(Duration.ofSeconds(5));
                        log.info("A2A abort fallback: killed CLI pid={} for taskId={}", pid, taskId);
                    }
                    return;
                }
            }
            log.debug("A2A abort fallback: no CLI process found for taskId={}", taskId);
        } catch (Exception e) {
            log.warn("A2A abort fallback failed for taskId={}: {}", taskId, e.getMessage());
        }
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

        // Soft-delete the associated SessionEntity (sets deletedAt) to prevent syncLocalSessions re-import
        String sessionId = entity.getSessionId();
        if (sessionId != null && sessionEntityRepository != null) {
            try {
                sessionEntityRepository.findById(sessionId).ifPresent(session -> {
                    session.setDeletedAt(LocalDateTime.now());
                    sessionEntityRepository.save(session);
                    log.info("Session soft-deleted: sessionId={}", sessionId);
                });
            } catch (Exception e) {
                log.warn("Failed to soft-delete session: sessionId={}", sessionId, e);
            }
        }

        // Also hard-delete from SessionManager (messages, etc.)
        taskRepository.delete(entity);
        if (sessionId != null) {
            try {
                sessionManager.deleteSession(sessionId);
                log.info("Associated session deleted from SessionManager: sessionId={}", sessionId);
            } catch (Exception e) {
                log.warn("Failed to delete associated session from SessionManager: sessionId={}", sessionId, e);
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

        // Batch load deleted session IDs to skip (from soft-deleted SessionEntity records)
        Set<String> deletedSessionIds = Set.of();
        if (sessionEntityRepository != null) {
            deletedSessionIds = sessionEntityRepository.findDeletedByWorkerIdAndUserId(workerId, userId)
                    .stream()
                    .map(s -> {
                        // Extract claudeSessionId from providerStateJson
                        String json = s.getProviderStateJson();
                        if (json != null && !json.isBlank()) {
                            try {
                                Map<String, Object> state = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
                                Object csId = state.get("claudeSessionId");
                                return csId != null ? csId.toString() : null;
                            } catch (Exception e) {
                                log.warn("Failed to parse providerStateJson for deleted session {}: {}", s.getId(), e.getMessage());
                            }
                        }
                        return null;
                    })
                    .filter(id -> id != null && !id.isEmpty())
                    .collect(Collectors.toSet());
        }

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
                    .providerType(AGENT_ID)
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

            persistTask(entity);

            // Auto-bind auth from WorkingDirectory default config
            if (matchedDir != null && matchedDir.getDefaultAuthMode() != null) {
                String[] dirAuth = workingDirectoryService.getDecryptedDefaultAuth(matchedDir);
                bindAuthToSession(
                        sessionId, workerId, userId, dirAuth[0], dirAuth[1], dirAuth[2],
                        matchedDir.getDefaultModelConfigId());
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
                persistTask(task);
                fixed++;
            }
        }
        return fixed;
    }

    /**
     * 解析 per-conversation auth 配置
     * 优先级：
     * 1. SessionEntity 已绑定 auth → 解密 token 返回
     * 2. 用户选择的平台 LLM 模型配置（modelConfigId）→ 覆盖目录配置
     * 3. WorkingDirectory 默认 auth 继承
     * 4. 全 null（Worker 用 .env 或 claude login）
     * 返回 [apiKey, authToken, baseUrl]（可能全 null 表示使用 Worker 全局默认）
     */
    private String[] resolveAuth(String sessionId, String workerId, String userId,
                                 String directoryId, String modelConfigId) {
        SessionEntity sessionForAuth = getOrCreateSessionEntity(sessionId, workerId, userId);

        if (sessionForAuth != null && sessionForAuth.getAuthBoundAt() != null) {
            // Already bound — decrypt and return
            String decryptedToken = getDecryptedTokenFromSession(sessionForAuth);
            String authMode = sessionForAuth.getAuthMode();
            String apiKey = null;
            String authToken = null;
            if ("API_KEY".equals(authMode) || "CUSTOM_ENDPOINT".equals(authMode)) {
                apiKey = decryptedToken;
            } else {
                authToken = decryptedToken;
            }
            return new String[]{apiKey, authToken, sessionForAuth.getAuthBaseUrl()};
        }

        // 用户选择的平台模型配置 → 优先于目录默认 auth
        // 仅当 SessionEntity 尚未绑定时，才使用并保存 modelConfigId 的 auth
        if (modelConfigId != null && !modelConfigId.isEmpty()) {
            // 校验 Worker 是否有权使用该模型
            llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
            LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig != null && Boolean.TRUE.equals(modelConfig.getHasApiKey())) {
                String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
                log.info("Auth resolved from platform model config: {}", modelConfig.getName());

                // 保存到 SessionEntity
                String authMode = (modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isEmpty())
                        ? "CUSTOM_ENDPOINT" : "API_KEY";
                bindAuthToSession(
                        sessionId, workerId, userId, authMode, decryptedApiKey, modelConfig.getBaseUrl(),
                        modelConfigId);

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
                        bindAuthToSession(
                                sessionId, workerId, userId, authMode, decryptedApiKey, dirModelConfig.getBaseUrl(),
                                dir.getDefaultModelConfigId());
                        return new String[]{decryptedApiKey, null, dirModelConfig.getBaseUrl()};
                    }
                }
                // 手动 auth 配置 fallback
                if (dir.getDefaultAuthMode() != null) {
                    String[] dirAuth = workingDirectoryService.getDecryptedDefaultAuth(dir);
                    bindAuthToSession(
                            sessionId, workerId, userId, dirAuth[0], dirAuth[1], dirAuth[2],
                            null);
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
     * 解析有效的 modelConfigId — 多级优先级链
     * <p>
     * 优先级：
     * 1. 显式指定（调用方每次传入）
     * 2. AgentModelOverride（租户管理员级覆盖，agent_model_override 表）
     * 3. CodingAgentEntity.defaultModelConfigId（Agent 创建者设置）
     * 4-6: 由 resolveAuth 内部处理（directory 默认 → 手动 auth → null）
     */
    private String resolveEffectiveModelConfigId(String explicitId, String agentId, String tenantId) {
        // 优先级 1：调用方显式指定
        if (explicitId != null && !explicitId.isEmpty()) {
            return explicitId;
        }

        if (agentId != null && !agentId.isEmpty()) {
            // 优先级 2：AgentModelOverride（租户管理员覆盖）
            // resolveModelForAgent 先查 override 表，再查 category default
            // 传 null category → category default 无结果 → 仅取 override 部分
            try {
                Optional<LlmModelConfigDTO> override = llmModelManager.resolveModelForAgent(tenantId, agentId, null);
                if (override.isPresent()) {
                    log.info("ModelConfig resolved from AgentModelOverride: agentId={}, configId={}",
                            agentId, override.get().getId());
                    return override.get().getId();
                }
            } catch (Exception e) {
                log.debug("AgentModelOverride lookup skipped for agentId={}: {}", agentId, e.getMessage());
            }

            // 优先级 3：CodingAgentEntity.defaultModelConfigId
            CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
            if (agentEntity != null && agentEntity.getDefaultModelConfigId() != null
                    && !agentEntity.getDefaultModelConfigId().isEmpty()) {
                log.info("ModelConfig resolved from Agent entity default: agentId={}, configId={}",
                        agentId, agentEntity.getDefaultModelConfigId());
                return agentEntity.getDefaultModelConfigId();
            }
        }

        return null; // 继续由 resolveAuth 内的 directory 优先级处理
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
     * [DISABLED] 超时检查 — 仅记录警告日志，不再自动标记 FAILED。
     *
     * 设计原则简化（Rule 2: 只有用户能终止 CLI）：
     * - CLI alive = task alive（唯一真相源）
     * - 超时由 Reconciler 检测 CLI 存活状态决定，不再主动标记 FAILED
     * - 原 @Scheduled 注解已移除，此方法仅供调试时手动调用
     */
    // @Scheduled removed — timeout checks are advisory only, Reconciler handles lifecycle
    @Transactional(readOnly = true)
    public void logTimeoutWarnings() {
        LocalDateTime now = LocalDateTime.now();
        int warnCount = 0;

        // 交互式编程任务：4 小时阈值
        LocalDateTime runningCutoff = now.minusHours(4);
        List<ClaudeTaskEntity> runningOld = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", runningCutoff);
        for (ClaudeTaskEntity entity : runningOld) {
            if (Boolean.FALSE.equals(entity.getFileCheckpointingEnabled())) {
                continue;
            }
            LocalDateTime baseline = entity.getLastAliveAt() != null ? entity.getLastAliveAt() : entity.getCreatedAt();
            if (baseline.isBefore(runningCutoff)) {
                log.warn("[Advisory] Task {} running > 4h (baseline={}), CLI lifecycle managed by Reconciler",
                        entity.getTaskId(), baseline);
                warnCount++;
            }
        }

        // Tracked sync 任务：10 分钟阈值
        LocalDateTime syncCutoff = now.minusMinutes(10);
        List<ClaudeTaskEntity> syncOld = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", syncCutoff);
        for (ClaudeTaskEntity entity : syncOld) {
            if (Boolean.FALSE.equals(entity.getFileCheckpointingEnabled())) {
                log.warn("[Advisory] Sync task {} running > 10min (created={}), CLI lifecycle managed by Reconciler",
                        entity.getTaskId(), entity.getCreatedAt());
                warnCount++;
            }
        }

        // AWAITING_PERMISSION 任务：4 小时阈值
        LocalDateTime permissionCutoff = now.minusHours(4);
        List<ClaudeTaskEntity> permOld = taskRepository.findByStatusAndCreatedAtBefore("AWAITING_PERMISSION", permissionCutoff);
        for (ClaudeTaskEntity entity : permOld) {
            LocalDateTime baseline = entity.getLastAliveAt() != null ? entity.getLastAliveAt() : entity.getCreatedAt();
            if (baseline.isBefore(permissionCutoff)) {
                log.warn("[Advisory] Permission task {} waiting > 4h (baseline={}), CLI lifecycle managed by Reconciler",
                        entity.getTaskId(), baseline);
                warnCount++;
            }
        }

        if (warnCount > 0) {
            log.info("[Advisory] {} tasks exceed timeout thresholds (no action taken — CLI lifecycle managed by Reconciler)", warnCount);
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
            persistTask(entity);
        });
    }

    /**
     * Reconciler 专用：CLI 已确认退出时，将任务标记为 COMPLETED。
     * Rule 1: CLI alive = task alive → CLI 退出 = 任务完成。
     * 仅对仍处于活跃状态（RUNNING / AWAITING_PERMISSION）的任务生效，幂等安全。
     */
    @Transactional
    public void reconcilerCompleteTask(String taskId, String reason) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String status = entity.getStatus();
            if ("RUNNING".equals(status) || "AWAITING_PERMISSION".equals(status)) {
                completeWithReason(entity, reason);
            }
        });
    }

    /**
     * SSE 流断开时调用（未收到 result/error 事件，且重连已失败）。
     *
     * 简化原则（Rule 1: CLI alive = task alive）：
     * - CLI 仍存活 → defer to Reconciler（不标记任何终态）
     * - CLI 已死 → 标记 COMPLETED（CLI 退出 = 任务完成）
     * - AWAITING_PERMISSION → defer to Reconciler（CLI 通常仍存活等待用户输入）
     */
    @Transactional
    public void handleStreamDisconnect(String taskId, String sessionId, String reason) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String status = entity.getStatus();
            if ("AWAITING_PERMISSION".equals(status)) {
                log.info("SSE stream ended while task awaiting permission — deferring to Reconciler: taskId={}", taskId);
                return;
            }
            if ("RUNNING".equals(status)) {
                if (isCliProcessAlive(entity)) {
                    log.info("CLI process still alive — deferring to Reconciler: taskId={}", taskId);
                    return;
                }

                // CLI dead + stream disconnected → mark COMPLETED (Rule 1: CLI exited = task done)
                String prev = entity.getStatus();
                entity.setStatus("COMPLETED");
                entity.setErrorMessage(reason);
                persistTask(entity);
                log.info("Task stream ended, CLI exited — marking COMPLETED: taskId={}, reason={}", taskId, reason);
                publishStatusChange(entity, prev);
                updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY");

                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("COMPLETED")
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
        persistTask(entity);
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
                    .getTaskStatus(resolveWorkerTaskLookupId(entity))
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
        persistTask(entity);
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
        String taskId = resolveWorkerTaskLookupId(task);

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
        persistTask(task);
        log.info("Resync: Task marked as COMPLETED from sync: taskId={}", task.getTaskId());
        publishStatusChange(task, prev);
        updateSessionInteractionState(task.getSessionId(), "AWAITING_REPLY");
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

    /**
     * 标记任务为 COMPLETED（CLI 已退出，任务完成）。
     *
     * Rule 1: CLI alive = task alive → CLI 退出 = 任务完成。
     * 不再自动 abort Worker（Rule 2: 只有用户能杀 CLI）。
     * 保留本地 SSE 流清理（释放 Java 侧资源）。
     */
    private void completeWithReason(ClaudeTaskEntity entity, String reason) {
        String prev = entity.getStatus();
        entity.setStatus("COMPLETED");
        entity.setErrorMessage(reason);
        persistTask(entity);
        log.info("Task completed (CLI exited): taskId={}, createdAt={}, reason={}",
                entity.getTaskId(), entity.getCreatedAt(), reason);
        publishStatusChange(entity, prev);
        updateSessionInteractionState(entity.getSessionId(), "AWAITING_REPLY");

        String taskId = entity.getTaskId();

        // 推送信息消息到会话（非错误，仅通知）
        publishSessionError(entity.getSessionId(), taskId, reason, false);

        // 清理本地 SSE 流订阅（释放 Java 侧资源，不影响 CLI）
        try {
            streamRelay.abortStream(taskId);
        } catch (Exception e) {
            log.warn("Failed to abort local stream on completion: taskId={}", taskId, e.getMessage());
        }

        // 发布跨 Agent 任务完成事件
        eventPublisher.publishEvent(TaskCompletionEvent.builder()
                .externalTaskId(taskId)
                .parentSessionId(entity.getSessionId())
                .targetAgentId(AGENT_ID)
                .status("COMPLETED")
                .resultSummary(reason)
                .build());
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

    /**
     * 解析调用 Worker /subscribe /status /abort 时应使用的任务标识。
     * 优先使用持久化的 workerTaskId，缺失时退回 Foggy taskId（worker 支持 foggy_task_id 别名）。
     */
    public String resolveWorkerTaskLookupId(ClaudeTaskEntity task) {
        if (task == null) {
            return null;
        }
        if (task.getWorkerTaskId() != null && !task.getWorkerTaskId().isBlank()) {
            return task.getWorkerTaskId();
        }
        return task.getTaskId();
    }

    // ==================== Session direct-access helpers (replacing ConversationConfigService) ====================

    /**
     * 更新会话交互状态（直接操作 SessionEntity，替代 ConversationConfigService.updateInteractionState）
     */
    private void updateSessionInteractionState(String sessionId, String state) {
        if (sessionEntityRepository == null || sessionId == null) return;
        sessionEntityRepository.findById(sessionId).ifPresent(session -> {
            session.setInteractionState(state);
            session.setLastActivityAt(LocalDateTime.now());
            sessionEntityRepository.save(session);
        });
    }

    /**
     * 清空 Session 的 claudeSessionId（用于首轮回退后重新开始对话）。
     * 同时清除 providerStateJson 中的 claudeSessionId 值。
     */
    private void clearClaudeSessionId(String sessionId) {
        if (sessionEntityRepository == null || sessionId == null) return;
        sessionEntityRepository.findById(sessionId).ifPresent(session -> {
            String providerState = session.getProviderStateJson();
            if (providerState != null && !providerState.isBlank()) {
                session.setProviderStateJson(
                        mergeJsonValue(providerState, "claudeSessionId", null)
                );
            }
            sessionEntityRepository.save(session);
        });
    }

    /**
     * 获取或创建 SessionEntity（替代 ConversationConfigService.getOrCreate）
     */
    private SessionEntity getOrCreateSessionEntity(String sessionId, String workerId, String userId) {
        if (sessionEntityRepository == null) return null;
        SessionEntity existing = sessionEntityRepository.findById(sessionId).orElse(null);
        if (existing != null) {
            boolean changed = false;
            if ((existing.getUserId() == null || existing.getUserId().isBlank()) && userId != null && !userId.isBlank()) {
                existing.setUserId(userId);
                changed = true;
            }
            if ((existing.getCurrentWorkerId() == null || existing.getCurrentWorkerId().isBlank()) && workerId != null && !workerId.isBlank()) {
                existing.setCurrentWorkerId(workerId);
                changed = true;
            }
            if (existing.getProviderType() == null || existing.getProviderType().isBlank()) {
                existing.setProviderType(AGENT_ID);
                changed = true;
            }
            if (changed) {
                return sessionEntityRepository.save(existing);
            }
            return existing;
        }

        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setProviderType(AGENT_ID);
        session.setCurrentWorkerId(workerId);
        session.setStatus("ACTIVE");
        session.setInteractionState("PROCESSING");
        session.setLastActivityAt(LocalDateTime.now());
        return sessionEntityRepository.save(session);
    }

    /**
     * 将 auth 信息直接绑定到 SessionEntity（替代 ConversationConfigService.bindAuthFromDirectory）
     */
    private void bindAuthToSession(String sessionId, String workerId, String userId,
                                   String authMode, String plainToken, String baseUrl,
                                   String modelConfigId) {
        if (sessionEntityRepository == null) return;
        SessionEntity session = getOrCreateSessionEntity(sessionId, workerId, userId);
        if (session == null || session.getAuthBoundAt() != null) return;
        session.setAuthMode(authMode);
        if (plainToken != null && !plainToken.isEmpty()) {
            session.setAuthTokenCiphertext(credentialEncryptor.encrypt(plainToken));
        }
        session.setAuthBaseUrl(blankToNull(baseUrl));
        session.setAuthBoundAt(LocalDateTime.now());
        session.setAuthModelConfigId(modelConfigId);
        sessionEntityRepository.save(session);
        log.info("Auth bound to session {}: mode={}, modelConfigId={}", sessionId, authMode, modelConfigId);
    }

    /**
     * 从 SessionEntity 解密 auth token（替代 ConversationConfigService.getDecryptedToken）
     */
    private String getDecryptedTokenFromSession(SessionEntity session) {
        if (session == null || session.getAuthTokenCiphertext() == null) return null;
        return credentialEncryptor.decrypt(session.getAuthTokenCiphertext());
    }

    /**
     * 锁定 agentTeamsConfigId 到 SessionEntity 的 providerStateJson
     */
    private void lockAgentTeamsConfigToSession(String sessionId, String workerId, String userId, String configId) {
        if (sessionEntityRepository == null || configId == null) return;
        SessionEntity session = getOrCreateSessionEntity(sessionId, workerId, userId);
        if (session == null) return;
        session.setProviderStateJson(mergeJsonValue(session.getProviderStateJson(), "agentTeamsConfigId", configId));
        sessionEntityRepository.save(session);
    }

    /**
     * 从 SessionEntity.providerStateJson 读取 agentTeamsConfigId
     */
    private String readAgentTeamsConfigId(String sessionId) {
        if (sessionEntityRepository == null) return null;
        return sessionEntityRepository.findById(sessionId)
                .map(session -> readJsonValue(session.getProviderStateJson(), "agentTeamsConfigId"))
                .orElse(null);
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

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private List<String> findSessionIdsByInteractionStateDirect(String userId, String state) {
        if (sessionEntityRepository == null) return List.of();
        return sessionEntityRepository.findSessionIdsByInteractionState(userId, state);
    }

    private List<String> findSessionIdsByInteractionStatesDirect(String userId, List<String> states) {
        if (sessionEntityRepository == null) return List.of();
        return sessionEntityRepository.findSessionIdsByInteractionStateIn(userId, states);
    }

    private List<String> findSessionIdsByTitleKeywordDirect(String userId, String keyword) {
        if (sessionEntityRepository == null) return List.of();
        return sessionEntityRepository.findSessionIdsByTitleKeyword(userId, keyword);
    }

    private List<String> findSessionIdsByTagKeywordDirect(String userId, String keyword) {
        if (sessionEntityRepository == null) return List.of();
        return sessionEntityRepository.findSessionIdsByTagKeyword(userId, keyword);
    }

    // ==================== End Session helpers ====================

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private Map<String, Object> buildRewindResult(String taskId, String checkpointId, String userPrompt,
                                                  Integer turnIndex, String claudeSessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "rewound");
        if (checkpointId != null && !checkpointId.isBlank()) {
            result.put("checkpointId", checkpointId);
        }
        result.put("taskId", taskId);
        result.put("userPrompt", userPrompt != null ? userPrompt : "");
        result.put("turnIndex", turnIndex);
        result.put("claudeSessionId", claudeSessionId);
        return result;
    }

    private void truncateSessionMessagesQuietly(String sessionId, int fromUserTurnIndex) {
        try {
            truncateSessionMessages(sessionId, fromUserTurnIndex);
        } catch (Exception dbEx) {
            log.warn("Failed to truncate DB messages for session {}: {}", sessionId, dbEx.getMessage());
        }
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

    private ClaudeTaskEntity persistTask(ClaudeTaskEntity entity) {
        ClaudeTaskEntity saved = taskRepository.save(entity);
        syncSessionTask(saved);
        syncSessionProjection(saved);
        return saved;
    }

    private void syncSessionTask(ClaudeTaskEntity entity) {
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
        sessionTask.setAgentId(agentId);
        // ClaudeTaskEntity 没有 tenantId 字段，从 SessionEntity 获取
        if (sessionEntityRepository != null && entity.getSessionId() != null) {
            sessionEntityRepository.findById(entity.getSessionId())
                    .ifPresent(session -> sessionTask.setTenantId(session.getTenantId()));
        }
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
        sessionTask.setTaskStateJson(buildClaudeTaskStateJson(entity));
        sessionTaskRepository.save(sessionTask);
    }

    private void syncSessionProjection(ClaudeTaskEntity entity) {
        if (sessionEntityRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        String agentId = resolveLogicalAgentId(entity);
        sessionEntityRepository.findById(entity.getSessionId()).ifPresent(session -> {
            session.setAgentId(firstNonBlank(session.getAgentId(), agentId));
            session.setProviderType(firstNonBlank(session.getProviderType(), AGENT_ID));
            session.setCurrentWorkerId(firstNonBlank(entity.getWorkerId(), session.getCurrentWorkerId()));
            session.setCurrentDirectoryId(firstNonBlank(entity.getDirectoryId(), session.getCurrentDirectoryId()));
            session.setLatestTaskId(entity.getTaskId());
            session.setLatestModel(firstNonBlank(entity.getModel(), session.getLatestModel()));
            session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
            session.setProviderStateJson(mergeJsonValue(
                    mergeJsonValue(session.getProviderStateJson(), "claudeSessionId", entity.getClaudeSessionId()),
                    "agentTeamsConfigId", entity.getAgentTeamsConfigId()));
            sessionEntityRepository.save(session);
        });
    }

    private String buildClaudeTaskStateJson(ClaudeTaskEntity entity) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, "claudeSessionId", entity.getClaudeSessionId());
        putIfNotBlank(state, "contextId", entity.getContextId());
        putIfNotBlank(state, "dedupKey", entity.getDedupKey());
        putIfNotBlank(state, "agentTeamsConfigId", entity.getAgentTeamsConfigId());
        if (entity.getFileCheckpointingEnabled() != null) {
            state.put("fileCheckpointingEnabled", entity.getFileCheckpointingEnabled());
        }
        if (entity.getCheckpoints() != null && !entity.getCheckpoints().isBlank()) {
            try {
                state.put("checkpoints", OBJECT_MAPPER.readValue(entity.getCheckpoints(), Object.class));
            } catch (Exception e) {
                state.put("checkpoints", entity.getCheckpoints());
            }
        }
        if (state.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Claude task state", e);
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
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // ===== 会话搜索 =====

    /**
     * 搜索会话：支持关键词（标题/提示词/标签）+ Worker/目录过滤。
     * 关键词在 prompt、customTitle、tags 三个字段上做 union 匹配；
     * Worker/目录过滤做 intersect 取交集。
     */
    public SessionSearchResultDTO.Page searchSessions(
            String userId, String keyword, String workerId,
            String directoryId, int page, int size) {

        Set<String> candidateSessionIds = null; // null = 尚未设置任何过滤条件

        // 1. 关键词搜索：union(prompt ∪ customTitle ∪ tags)
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            Set<String> textMatches = new LinkedHashSet<>();
            textMatches.addAll(taskRepository.findSessionIdsByPromptKeyword(userId, trimmed));
            textMatches.addAll(findSessionIdsByTitleKeywordDirect(userId, trimmed));
            textMatches.addAll(findSessionIdsByTagKeywordDirect(userId, trimmed));
            candidateSessionIds = textMatches;
        }

        // 2. Worker 过滤：intersect
        if (workerId != null && !workerId.isBlank()) {
            Set<String> workerSessions = new LinkedHashSet<>(
                    taskRepository.findSessionIdsByWorker(userId, workerId));
            candidateSessionIds = (candidateSessionIds == null)
                    ? workerSessions
                    : intersect(candidateSessionIds, workerSessions);
        }

        // 3. 目录过滤：intersect
        if (directoryId != null && !directoryId.isBlank()) {
            Set<String> dirSessions = new LinkedHashSet<>(
                    taskRepository.findSessionIdsByDirectory(userId, directoryId));
            candidateSessionIds = (candidateSessionIds == null)
                    ? dirSessions
                    : intersect(candidateSessionIds, dirSessions);
        }

        // 4. 无过滤条件 → 返回空（搜索弹窗至少需要输入关键词或选择过滤条件）
        if (candidateSessionIds == null || candidateSessionIds.isEmpty()) {
            return SessionSearchResultDTO.Page.builder()
                    .results(List.of()).total(0).page(page).size(size).build();
        }

        long total = candidateSessionIds.size();

        // 5. 分页排序：按最新任务时间 desc
        List<String> pagedSessionIds = taskRepository.findDistinctSessionIdsByUserFilteredBySessionIds(
                userId, new ArrayList<>(candidateSessionIds), PageRequest.of(page, size));

        if (pagedSessionIds.isEmpty()) {
            return SessionSearchResultDTO.Page.builder()
                    .results(List.of()).total(total).page(page).size(size).build();
        }

        // 6. 获取每个 session 的所有任务（用于计算费用和取 firstPrompt）
        List<ClaudeTaskEntity> allTasks = taskRepository
                .findBySessionIdInAndUserIdOrderByCreatedAtDesc(pagedSessionIds, userId);

        // 7. 获取每个 session 的最新任务
        List<ClaudeTaskEntity> latestTasks = taskRepository.findLatestBySessionIdIn(pagedSessionIds);

        // 8. 获取会话配置（直接查 SessionEntity）
        Map<String, SessionEntity> configMap = sessionEntityRepository != null
                ? sessionEntityRepository.findAllById(pagedSessionIds).stream()
                    .collect(Collectors.toMap(SessionEntity::getId, s -> s))
                : Map.of();

        // 9. 计算每个 session 的总费用
        Map<String, BigDecimal> costMap = allTasks.stream()
                .collect(Collectors.groupingBy(ClaudeTaskEntity::getSessionId,
                        Collectors.reducing(BigDecimal.ZERO,
                                t -> t.getCostUsd() != null ? t.getCostUsd() : BigDecimal.ZERO,
                                BigDecimal::add)));

        // 10. 按 session 分组取 firstPrompt（最早任务的 prompt）
        Map<String, String> firstPromptMap = allTasks.stream()
                .collect(Collectors.groupingBy(ClaudeTaskEntity::getSessionId,
                        Collectors.collectingAndThen(
                                Collectors.minBy((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt())),
                                opt -> opt.map(ClaudeTaskEntity::getPrompt).orElse(""))));

        // 11. 构建结果
        List<SessionSearchResultDTO> results = latestTasks.stream().map(task -> {
            SessionEntity config = configMap.get(task.getSessionId());
            String firstPrompt = firstPromptMap.getOrDefault(task.getSessionId(), task.getPrompt());

            return SessionSearchResultDTO.builder()
                    .sessionId(task.getSessionId())
                    .workerId(task.getWorkerId())
                    .directoryId(task.getDirectoryId())
                    .firstPrompt(truncate(firstPrompt, 200))
                    .customTitle(config != null ? config.getTitle() : null)
                    .tags(config != null ? parseTagsJson(config.getTagsJson()) : List.of())
                    .interactionState(config != null ? config.getInteractionState() : null)
                    .latestTaskId(task.getTaskId())
                    .latestStatus(task.getStatus())
                    .model(task.getModel())
                    .modelConfigId(config != null ? config.getAuthModelConfigId() : task.getModelConfigId())
                    .cwd(task.getCwd())
                    .source(task.getSource())
                    .totalCost(costMap.getOrDefault(task.getSessionId(), BigDecimal.ZERO))
                    .createdAt(task.getCreatedAt())
                    .updatedAt(task.getUpdatedAt())
                    .build();
        }).toList();

        return SessionSearchResultDTO.Page.builder()
                .results(results).total(total).page(page).size(size).build();
    }

    private <T> Set<T> intersect(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private List<String> parseTagsJson(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return Collections.emptyList();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(tagsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ── TaskQueryProvider 实现 ──

    @Override
    public String getProviderType() {
        return AGENT_ID;
    }

    @Override
    public void cancelTask(String taskId, String userId) {
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found or access denied: " + taskId);
        }
        abortTask(taskId);
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
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of("RUNNING", "AWAITING_PERMISSION")).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public DispatchTaskDTO createTaskDirect(java.util.Map<String, Object> params,
                                             String userId, String tenantId) {
        // 构造旧 CreateTaskForm，复用完整的 createTask 路径（含 session 创建 + 事件发布）
        CreateTaskForm form = new CreateTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setPermissionMode((String) params.get("permissionMode"));
        form.setImages((String) params.get("images"));
        form.setAgentTeamsJson((String) params.get("agentTeamsJson"));
        form.setAgentTeamsConfigId((String) params.get("agentTeamsConfigId"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        TaskDTO taskDTO = createTask(userId, tenantId, form);
        // 转为统一 DispatchTaskDTO
        return getTaskById(taskDTO.getTaskId()).orElseThrow();
    }

    @Override
    public void respondToTask(String taskId, String userId, java.util.Map<String, Object> response) {
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!task.getUserId().equals(userId)) throw new IllegalArgumentException("Task not found: " + taskId);

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        ClaudeWorkerClient client = workerService.createClient(worker);
        client.respondToPermission(
                task.getWorkerTaskId() != null ? task.getWorkerTaskId() : resolveWorkerTaskLookupId(task),
                (String) response.get("permissionId"),
                (String) response.get("decision"),
                (String) response.get("denyMessage"),
                (String) response.get("scope"),
                castToStringMap(response.get("answers")),
                (String) response.get("planAction")
        ).block(java.time.Duration.ofSeconds(10));

        // 回到 RUNNING 状态
        if ("AWAITING_PERMISSION".equals(task.getStatus())) {
            task.setStatus("RUNNING");
            persistTask(task);
        }
    }

    @Override
    public void reconnectTask(String taskId, String userId) {
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!task.getUserId().equals(userId)) throw new IllegalArgumentException("Task not found: " + taskId);
        streamRelay.reconnectTask(taskId, task.getSessionId(), task.getWorkerId());
    }

    @Override
    public Object resyncTask(String taskId, String userId) {
        return resync(taskId, userId);
    }

    @Override
    @Transactional
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        ClaudeTaskEntity task = getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if ("RUNNING".equals(task.getStatus()) || "AWAITING_PERMISSION".equals(task.getStatus())) {
            throw new IllegalStateException("Cannot rewind a running task");
        }

        String claudeSessionId = task.getClaudeSessionId();
        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            throw new IllegalArgumentException("Task has no Claude session ID");
        }

        String mode = params.get("mode") != null ? params.get("mode").toString() : "file_rewind";
        Integer turnIndex = params.get("turnIndex") instanceof Number n ? n.intValue() : null;

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        ClaudeWorkerClient client = workerService.createClient(worker);

        if ("conversation_fork".equals(mode)) {
            try {
                Map<String, Object> rewindResult = client.rewindConversation(
                                claudeSessionId, turnIndex != null ? turnIndex : 1)
                        .block(Duration.ofSeconds(15));

                String rewindStatus = rewindResult != null ? (String) rewindResult.get("status") : "error";
                if ("error".equals(rewindStatus)) {
                    throw new IllegalStateException("回退会话失败: " + rewindResult.get("message"));
                }

                String userPrompt = rewindResult != null ? (String) rewindResult.get("user_prompt") : "";
                int effectiveTurn = turnIndex != null ? turnIndex : 1;
                truncateSessionMessagesQuietly(task.getSessionId(), effectiveTurn);

                // 首轮回退：Worker 已删除 session 文件，清空 claudeSessionId
                // 后续消息将以全新 CLI 会话启动，不再尝试 --resume
                Boolean sessionCleared = rewindResult != null ? (Boolean) rewindResult.get("session_cleared") : null;
                String effectiveClaudeSessionId = claudeSessionId;
                if (Boolean.TRUE.equals(sessionCleared)) {
                    clearClaudeSessionId(task.getSessionId());
                    effectiveClaudeSessionId = null;
                    log.info("First-turn rewind: cleared claudeSessionId for session {}", task.getSessionId());
                }

                return buildRewindResult(taskId, null, userPrompt, turnIndex, effectiveClaudeSessionId);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to rewind conversation: taskId={}, error={}", taskId, e.getMessage());
                throw new IllegalStateException("回退失败: " + e.getMessage(), e);
            }
        }

        String checkpointId = params.get("checkpointId") != null ? params.get("checkpointId").toString() : null;
        if (checkpointId == null || checkpointId.isEmpty()) {
            throw new IllegalArgumentException("checkpointId is required for file_rewind mode");
        }

        try {
            client.rewindFiles(claudeSessionId, checkpointId, task.getCwd())
                    .block(Duration.ofSeconds(30));

            String userPrompt = "";
            int effectiveTurn = turnIndex != null ? turnIndex : 1;
            String effectiveClaudeSessionId = claudeSessionId;
            try {
                Map<String, Object> convResult = client.rewindConversation(claudeSessionId, effectiveTurn)
                        .block(Duration.ofSeconds(15));
                if (convResult != null && convResult.get("user_prompt") != null) {
                    userPrompt = convResult.get("user_prompt").toString();
                }
                // 首轮回退：Worker 已删除 session 文件
                if (convResult != null && Boolean.TRUE.equals(convResult.get("session_cleared"))) {
                    clearClaudeSessionId(task.getSessionId());
                    effectiveClaudeSessionId = null;
                    log.info("First-turn file rewind: cleared claudeSessionId for session {}", task.getSessionId());
                }
            } catch (Exception convEx) {
                log.warn("File rewind succeeded but conversation rewind failed for task {}: {}", taskId, convEx.getMessage());
            }

            truncateSessionMessagesQuietly(task.getSessionId(), effectiveTurn);
            return buildRewindResult(taskId, checkpointId, userPrompt, turnIndex, effectiveClaudeSessionId);
        } catch (Exception e) {
            log.warn("Failed to rewind files: taskId={}, error={}", taskId, e.getMessage());
            throw new IllegalStateException("回退失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DispatchTaskDTO resumeTask(String userId, String tenantId, java.util.Map<String, Object> params) {
        ResumeTaskForm form = new ResumeTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setPermissionMode((String) params.get("permissionMode"));
        form.setImages((String) params.get("images"));
        form.setAgentTeamsJson((String) params.get("agentTeamsJson"));
        form.setAgentTeamsConfigId((String) params.get("agentTeamsConfigId"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        // claudeSessionId 从 SessionEntity.providerStateJson 恢复，不再从 request 透传
        String sessionId = form.getSessionId();
        String claudeSessionId = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            claudeSessionId = readJsonValue(
                    sessionEntityRepository.findById(sessionId)
                            .map(SessionEntity::getProviderStateJson).orElse(null),
                    "claudeSessionId");
        }

        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            // 首轮回退后 claudeSessionId 已被清空，降级为 createTask（新建 CLI 会话，复用 sessionId）
            log.info("resumeTask fallback to createTask: claudeSessionId is null for session {}", sessionId);
            CreateTaskForm createForm = new CreateTaskForm();
            createForm.setAgentId(form.getAgentId());
            createForm.setWorkerId(form.getWorkerId());
            createForm.setPrompt(form.getPrompt());
            createForm.setCwd(form.getCwd());
            createForm.setDirectoryId(form.getDirectoryId());
            createForm.setSessionId(sessionId);
            createForm.setModel(form.getModel());
            createForm.setModelConfigId(form.getModelConfigId());
            createForm.setPermissionMode(form.getPermissionMode());
            createForm.setImages(form.getImages());
            createForm.setMaxTurns(form.getMaxTurns());
            createForm.setAgentTeamsJson(form.getAgentTeamsJson());
            createForm.setAgentTeamsConfigId(form.getAgentTeamsConfigId());
            TaskDTO taskDTO = createTask(userId, tenantId, createForm);
            return getTaskById(taskDTO.getTaskId()).orElseThrow();
        }

        form.setClaudeSessionId(claudeSessionId);
        TaskDTO taskDTO = resumeTask(userId, tenantId, form);
        return getTaskById(taskDTO.getTaskId()).orElseThrow();
    }

    // deleteTask(String userId, String taskId) is already defined above at line ~986
    // and satisfies the SPI interface TaskQueryProvider.deleteTask(userId, taskId)

    @Override
    public Object scanCheckpoints(String taskId, String userId) {
        ClaudeTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        String claudeSessionId = task.getClaudeSessionId();
        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            throw new IllegalArgumentException("Task has no Claude session ID");
        }

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        ClaudeWorkerClient client = workerService.createClient(worker);
        List<java.util.Map<String, Object>> scanned = client.scanSessionCheckpoints(claudeSessionId)
                .block(java.time.Duration.ofSeconds(30));
        if (scanned == null || scanned.isEmpty()) {
            return java.util.Map.of("taskId", taskId, "checkpoints", "[]", "count", 0);
        }
        String json = scanAndPopulateCheckpoints(taskId, scanned);
        return java.util.Map.of("taskId", taskId, "checkpoints", json, "count", scanned.size());
    }

    @Override
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTasksBySession(userId, page, size, state);
    }

    // searchSessions(userId, keyword, workerId, directoryId, page, size) is already defined
    // above and satisfies the SPI interface (covariant return: SessionSearchResultDTO.Page → Object)

    @Override
    public List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        return taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public Object listTasksByDirectoryPaged(String userId, String directoryId,
                                             int page, int size, String state) {
        return listTasksByDirectorySession(userId, directoryId, page, size, state);
    }

    // ── Worker Session 查询 SPI 实现 ──

    @Override
    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found");
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            return sessions != null ? sessions : List.of();
        } catch (Exception e) {
            log.warn("Failed to list worker sessions: workerId={}, error={}", workerId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found");
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> result = client.getSessionMessageCount(sessionId)
                    .block(java.time.Duration.ofSeconds(10));
            return result != null ? result : Map.of("user_count", 0, "assistant_count", 0, "total", 0);
        } catch (Exception e) {
            log.warn("Failed to get message count: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return Map.of("user_count", 0, "assistant_count", 0, "total", 0);
        }
    }

    @Override
    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found");
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> messages;
            if (offset != null || limit != null) {
                messages = client.getSessionMessages(sessionId,
                        offset != null ? offset : 0, limit)
                        .block(java.time.Duration.ofSeconds(30));
            } else {
                messages = client.getSessionMessages(sessionId)
                        .block(java.time.Duration.ofSeconds(30));
            }
            return messages != null ? messages : List.of();
        } catch (Exception e) {
            log.warn("Failed to get session messages: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found");
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.syncSessions().block(java.time.Duration.ofSeconds(30));

            List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            if (sessions == null) sessions = List.of();

            int created = syncLocalSessions(userId, tenantId, workerId, sessions);
            return Map.of("synced", created, "total", sessions.size());
        } catch (Exception e) {
            log.warn("Failed to sync sessions on worker: workerId={}, error={}", workerId, e.getMessage());
            throw new RuntimeException("同步失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> castToStringMap(Object obj) {
        if (obj == null) return null;
        if (obj instanceof java.util.Map) return (java.util.Map<String, String>) obj;
        return null;
    }

    private DispatchTaskDTO toDispatchDTO(ClaudeTaskEntity entity) {
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
                .modelConfigId(entity.getModelConfigId())
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
                // Claude-specific
                .claudeSessionId(entity.getClaudeSessionId())
                .contextId(entity.getContextId())
                .checkpoints(entity.getCheckpoints())
                .fileCheckpointingEnabled(entity.getFileCheckpointingEnabled())
                .build();
    }

    private String resolveLogicalAgentId(@Nullable String requestedAgentId, @Nullable String sessionId) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            String sessionAgentId = resolveSessionAgentId(sessionId);
            if (sessionAgentId != null && !sessionAgentId.isBlank()) {
                return sessionAgentId;
            }
        }
        return AGENT_ID;
    }

    private String resolveLogicalAgentId(ClaudeTaskEntity entity) {
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
        if (sessionEntityRepository == null) {
            return null;
        }
        return sessionEntityRepository.findById(sessionId)
                .map(SessionEntity::getAgentId)
                .orElse(null);
    }

    private TaskDTO toDTO(ClaudeTaskEntity entity) {
        return TaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
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
                .modelConfigId(entity.getModelConfigId())
                .errorMessage(entity.getErrorMessage())
                .resultText(entity.getResultText())
                .contextId(entity.getContextId())
                .checkpoints(entity.getCheckpoints())
                .lastAckedSeq(entity.getLastAckedSeq())
                .fileCheckpointingEnabled(entity.getFileCheckpointingEnabled())
                .source(entity.getSource())
                .agentTeamsConfigId(entity.getAgentTeamsConfigId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

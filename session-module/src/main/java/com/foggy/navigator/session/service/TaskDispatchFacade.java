package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一任务分发 Facade —— 所有外部入口（前端 / OpenAPI / A2A）的唯一任务操作层。
 * <p>
 * 职责：
 * <ol>
 *   <li>验证/建立会话-Agent 绑定</li>
 *   <li>通过 UnifiedAgentResolver 解析目标 A2aAgent</li>
 *   <li>构造 A2aMessage 并委派到 Agent 执行</li>
 *   <li>返回统一 DispatchTaskDTO</li>
 * </ol>
 * <p>
 * Controller 只依赖本 Facade，不允许直接接触 A2aAgent / Provider / Worker。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatchFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    private final List<TaskQueryProvider> taskQueryProviders;
    private final LlmModelManager llmModelManager;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private WorkingDirectoryRepository workingDirectoryRepository;

    /**
     * 创建任务。
     * <p>
     * 1. 解析 Agent 查找键（显式 agentId → directoryId → workerId）
     * 2. 验证/建立 Session ↔ Agent 绑定
     * 3. 构造 A2aMessage，调用 A2aAgent.sendTask()
     * 4. 返回统一 DTO
     */
    public DispatchTaskDTO createTask(TaskDispatchRequest request, AgentResolveContext context) {
        CreateExecutionTarget target = resolveCreateExecutionTarget(request);
        if (target.directProviderRoute()) {
            return createTaskDirect(target.providerType(), request, context);
        }

        AgentLookup lookup = target.agentLookup();

        A2aAgent agent = agentResolver.resolveAgent(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + lookup.lookupId));

        String providerType = agentResolver.getProviderType(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + lookup.lookupId));

        String agentId = resolveLogicalAgentId(agent, lookup.lookupId);
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        // 绑定校验
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            bindingService.getOrBind(request.getSessionId(), agentId, providerType, lookup.bindingSource);
        }

        // 构造 A2aMessage
        A2aMessage message = buildMessage(request);

        // 执行
        A2aTask a2aTask = agent.sendTask(message);
        log.info("Dispatched task via Facade: agentId={}, providerType={}, taskId={}",
                agentId, providerType, a2aTask.getId());

        return toDispatchDTO(a2aTask, agentId, providerType, request);
    }

    /**
     * 查询单个任务（遍历所有 TaskQueryProvider）
     */
    public Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
        if (sessionTaskRepository != null) {
            Optional<DispatchTaskDTO> unified = context.getUserId() != null
                    ? sessionTaskRepository.findByTaskIdAndUserId(taskId, context.getUserId()).map(this::toDispatchTaskDTO)
                    : sessionTaskRepository.findByTaskId(taskId).map(this::toDispatchTaskDTO);
            if (unified.isPresent()) {
                return unified;
            }
        }

        for (TaskQueryProvider provider : taskQueryProviders) {
            Optional<DispatchTaskDTO> result = context.getUserId() != null
                    ? provider.getTaskByIdAndUser(taskId, context.getUserId())
                    : provider.getTaskById(taskId);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /**
     * 按会话查询任务列表（根据 session 绑定的 providerType 路由到对应 Provider）
     */
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId));
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return List.of();

        String providerType = session.getProviderType();
        if (providerType != null) {
            // 精确匹配 provider
            return taskQueryProviders.stream()
                    .filter(p -> providerType.equals(p.getProviderType()))
                    .findFirst()
                    .map(p -> p.listTasksBySession(sessionId))
                    .orElse(List.of());
        }

        // providerType 为空（旧会话），遍历所有 provider
        return taskQueryProviders.stream()
                .flatMap(p -> p.listTasksBySession(sessionId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 聚合所有 Provider 的活跃任务
     */
    public List<DispatchTaskDTO> listActiveTasks(String userId) {
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                            userId, List.of("RUNNING", "AWAITING_PERMISSION"))).stream()
                    .sorted((left, right) -> compareNullableTime(right.getCreatedAt(), left.getCreatedAt()))
                    .toList();
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        return taskQueryProviders.stream()
                .flatMap(p -> p.listActiveDispatchTasks(userId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId, String agentId, AgentResolveContext context) {
        // 优先尝试 A2a Agent 路径
        Optional<A2aAgent> agentOpt = agentResolver.resolveAgent(agentId, context);
        if (agentOpt.isPresent()) {
            if (context.getSessionId() != null) {
                bindingService.validateBinding(context.getSessionId(), agentId);
            }
            agentOpt.get().cancelTask(taskId);
            log.info("Cancelled task via A2a Agent: taskId={}, agentId={}", taskId, agentId);
            return;
        }

        // Fallback: 通过 TaskQueryProvider 路由取消（Codex 等无 A2a 实例的 provider）
        try {
            TaskQueryProvider provider = findProviderForTask(taskId);
            provider.cancelTask(taskId, context.getUserId());
            log.info("Cancelled task via Provider: taskId={}, providerType={}", taskId, provider.getProviderType());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot cancel task " + taskId + ": " + e.getMessage(), e);
        }
    }

    // ── 任务操作（路由到 TaskQueryProvider） ──

    /**
     * 回复权限请求 / 用户问题（不支持的 Provider 自动抛 UnsupportedOperationException）
     */
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        provider.respondToTask(taskId, userId, response);
    }

    /**
     * 重连任务 SSE 流
     */
    public void reconnectTask(String taskId, String userId) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        provider.reconnectTask(taskId, userId);
    }

    /**
     * 重新同步任务状态
     */
    public Object resyncTask(String taskId, String userId) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        return provider.resyncTask(taskId, userId);
    }

    /**
     * 回退到检查点
     */
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        return provider.rewindTask(taskId, userId, params);
    }

    // ── Phase 3: 统一任务端点扩展 ──

    /**
     * 恢复任务（resume）—— 续接已有会话。
     */
    public DispatchTaskDTO resumeTask(TaskDispatchRequest request, AgentResolveContext context) {
        String providerType = resolveResumeProviderType(request, context);
        normalizeResumeRequest(request, providerType);
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        // 找到对应 Provider 直接调用 resumeTask
        TaskQueryProvider provider = taskQueryProviders.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));

        Map<String, Object> params = buildResumeParams(request);
        return provider.resumeTask(context.getUserId(), context.getTenantId(), params);
    }

    /**
     * resume 优先复用 session 已绑定的 provider，避免跨 provider 误路由。
     * 仅当旧 session 尚未绑定 providerType 时，才允许通过显式 provider/modelConfig/agent 做迁移兜底。
     */
    private String resolveResumeProviderType(TaskDispatchRequest request, AgentResolveContext context) {
        String sessionId = request.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null) {
                String boundProviderType = session.getProviderType();
                if (boundProviderType != null && !boundProviderType.isBlank()) {
                    return boundProviderType;
                }
            }
        }
        return resolveResumeProviderTypeFromLegacyContext(request, context);
    }

    private String resolveResumeProviderTypeFromLegacyContext(TaskDispatchRequest request, AgentResolveContext context) {
        // 1. 从 modelConfigId 推导（优先于已废弃的 request.providerType）
        String fromModelConfig = resolveProviderTypeFromModelConfig(request.getModelConfigId());
        if (fromModelConfig != null && !fromModelConfig.isBlank()) {
            return fromModelConfig;
        }

        // 2. 从已废弃的 request.providerType（向后兼容 Open API）
        @SuppressWarnings("deprecation")
        String requestedProviderType = request.getProviderType();
        if (requestedProviderType != null && !requestedProviderType.isBlank()) {
            return requestedProviderType;
        }

        // 3. 从 agentId 推导（包括 directory# 格式）
        String agentLookupId = resolveBoundOrExplicitAgentId(request.getAgentId(), request.getSessionId());
        if (agentLookupId != null && !agentLookupId.isBlank()) {
            // directory# 格式的 agentId 需要通过目录的 modelConfigId 推导
            if (DirectoryAgentId.isDirectoryAgent(agentLookupId)) {
                String dirId = DirectoryAgentId.extractDirectoryId(agentLookupId);
                String dirModelConfigId = resolveModelConfigIdFromDirectory(null, dirId);
                if (dirModelConfigId != null) {
                    String pt = resolveProviderTypeFromModelConfig(dirModelConfigId);
                    if (pt != null) return pt;
                }
            }
            return agentResolver.getProviderType(agentLookupId, context)
                    .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + agentLookupId));
        }

        throw new IllegalArgumentException(
                "No provider found for resume request; old sessions require session.providerType or explicit providerType/modelConfigId/agentId");
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId, String userId) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        boolean shouldCleanupSessionStore = false;
        try {
            provider.deleteTask(userId, taskId);
            shouldCleanupSessionStore = true;
        } catch (IllegalArgumentException e) {
            if (!isProviderTaskAlreadyMissing(e, taskId)) {
                throw e;
            }
            log.warn("Provider task already missing during delete; cleaning unified session store only: taskId={}", taskId);
            shouldCleanupSessionStore = true;
        }

        if (shouldCleanupSessionStore && sessionTaskRepository != null) {
            sessionTaskRepository.deleteByTaskId(taskId);
        }
    }

    /**
     * 扫描 checkpoints
     */
    public Object scanCheckpoints(String taskId, String userId) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        return provider.scanCheckpoints(taskId, userId);
    }

    /**
     * 分页查询任务列表（按会话聚合所有 Provider，再统一分页）
     */
    public Object listTasksPaged(String userId, int page, int size, String state) {
        if (sessionTaskRepository != null) {
            Object unified = listTasksPagedFromSessionStore(userId, null, page, size, state);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                Object pageResult = provider.listTasksPaged(userId, 0, fetchSize, state);
                TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(envelope.content());
                totalSessions += envelope.totalSessions();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        return buildTaskPageResponse(content, totalSessions, page, size);
    }

    /**
     * 搜索会话
     */
    public Object searchSessions(String userId, String keyword, String workerId,
                                  String directoryId, int page, int size) {
        if (sessionTaskRepository != null) {
            Object unified = searchSessionsFromSessionStore(userId, keyword, workerId, directoryId, page, size);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> results = new ArrayList<>();
        long total = 0L;

        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                Object searchResult = provider.searchSessions(userId, keyword, workerId, directoryId, 0, fetchSize);
                SearchEnvelope envelope = toSearchEnvelope(searchResult);
                results.addAll(envelope.results());
                total += envelope.total();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        Map<String, Object> deduped = new LinkedHashMap<>();
        for (Object result : results) {
            String sessionId = readStringProperty(result, "sessionId");
            String dedupeKey = (sessionId != null && !sessionId.isBlank())
                    ? sessionId
                    : UUID.randomUUID().toString();
            Object existing = deduped.get(dedupeKey);
            if (existing == null || compareNullableTime(
                    readDateTimeProperty(result, "updatedAt"),
                    readDateTimeProperty(existing, "updatedAt")) > 0) {
                deduped.put(dedupeKey, result);
            }
        }

        List<Object> sortedResults = deduped.values().stream()
                .sorted((left, right) -> compareNullableTime(
                        readDateTimeProperty(right, "updatedAt"),
                        readDateTimeProperty(left, "updatedAt")))
                .toList();

        int from = Math.min(page * size, sortedResults.size());
        int to = Math.min(from + size, sortedResults.size());
        return Map.of(
                "results", sortedResults.subList(from, to),
                "total", total,
                "page", page,
                "size", size
        );
    }

    /**
     * 按目录查询任务列表
     */
    public List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId));
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        return taskQueryProviders.stream()
                .flatMap(p -> {
                    try {
                        return p.listTasksByDirectory(userId, directoryId).stream();
                    } catch (UnsupportedOperationException e) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
    }

    /**
     * 按目录分页查询任务列表
     */
    public Object listTasksByDirectoryPaged(String userId, String directoryId,
                                             int page, int size, String state) {
        if (sessionTaskRepository != null) {
            Object unified = listTasksPagedFromSessionStore(userId, directoryId, page, size, state);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                Object pageResult = provider.listTasksByDirectoryPaged(userId, directoryId, 0, fetchSize, state);
                TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(envelope.content());
                totalSessions += envelope.totalSessions();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        return buildTaskPageResponse(content, totalSessions, page, size);
    }

    private Map<String, Object> buildResumeParams(TaskDispatchRequest request) {
        return toCommonParams(request);
    }

    // ── Worker Session 查询（统一端点） ──

    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                return provider.listWorkerSessions(workerId, userId);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        return List.of();
    }

    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                return provider.getWorkerSessionMessageCount(workerId, sessionId, userId);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        return Map.of("user_count", 0, "assistant_count", 0, "total", 0);
    }

    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                return provider.getWorkerSessionMessages(workerId, sessionId, userId, offset, limit);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        return List.of();
    }

    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        for (TaskQueryProvider provider : taskQueryProviders) {
            try {
                return provider.syncWorkerSessions(workerId, userId, tenantId);
            } catch (UnsupportedOperationException ignored) {
            }
        }
        throw new UnsupportedOperationException("No provider supports syncWorkerSessions");
    }

    private TaskQueryProvider findProviderForTask(String taskId) {
        if (sessionTaskRepository != null) {
            String providerType = sessionTaskRepository.findByTaskId(taskId)
                    .map(SessionTaskEntity::getProviderType)
                    .orElse(null);
            if (providerType != null && !providerType.isBlank()) {
                return findTaskQueryProviderByType(providerType)
                        .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));
            }
        }

        for (TaskQueryProvider p : taskQueryProviders) {
            if (p.getTaskById(taskId).isPresent()) return p;
        }
        throw new IllegalArgumentException("Task not found: " + taskId);
    }

    private Optional<TaskQueryProvider> findTaskQueryProviderByType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return Optional.empty();
        }
        return taskQueryProviders.stream()
                .filter(provider -> providerType.equals(provider.getProviderType()))
                .findFirst();
    }

    private boolean isProviderTaskAlreadyMissing(IllegalArgumentException exception, String taskId) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.equals("Task not found: " + taskId) || message.contains("Task not found");
    }

    // ── 内部方法 ──

    private A2aMessage buildMessage(TaskDispatchRequest request) {
        List<A2aPart> parts = new ArrayList<>();
        parts.add(A2aPart.text(request.getPrompt()));

        // 复用公共参数转换，A2A message 的 metadata 和 Direct params 共享同一组字段
        Map<String, Object> metadata = toCommonParams(request);

        return A2aMessage.builder()
                .role("user")
                .parts(parts)
                .contextId(request.getContextId())
                .contextAlias(request.getContextAlias())
                .metadata(metadata)
                .build();
    }

    private DispatchTaskDTO toDispatchDTO(A2aTask a2aTask, String agentId, String providerType,
                                           TaskDispatchRequest request) {
        DispatchTaskDTO.DispatchTaskDTOBuilder builder = DispatchTaskDTO.builder()
                .taskId(a2aTask.getId())
                .agentId(agentId)
                .providerType(providerType)
                .contextId(a2aTask.getContextId());

        // 映射状态
        if (a2aTask.getStatus() != null) {
            builder.status(mapA2aState(a2aTask.getStatus().getState()));
        }

        // 从 A2aTask metadata 提取扩展字段
        Map<String, Object> meta = a2aTask.getMetadata();
        if (meta != null) {
            builder.sessionId(strVal(meta, "sessionId"))
                    .workerId(strVal(meta, "workerId"))
                    .workerTaskId(strVal(meta, "workerTaskId"))
                    .directoryId(strVal(meta, "directoryId"))
                    .claudeSessionId(strVal(meta, "claudeSessionId"))
                    .codexThreadId(strVal(meta, "codexThreadId"));
        }

        // 从 metadata 提取 source / fileCheckpointingEnabled（Provider 可能已设置）
        if (meta != null) {
            if (meta.get("source") instanceof String s) builder.source(s);
            if (meta.get("fileCheckpointingEnabled") instanceof Boolean b) builder.fileCheckpointingEnabled(b);
        }

        // 从 request 补充（如果 metadata 里没有）
        if (request != null) {
            DispatchTaskDTO dto = builder.build();
            if (dto.getSessionId() == null) builder.sessionId(request.getSessionId());
            if (dto.getWorkerId() == null) builder.workerId(request.getWorkerId());
            if (dto.getDirectoryId() == null) builder.directoryId(request.getDirectoryId());
            // 通过 Facade 创建的任务均来自平台
            if (dto.getSource() == null) builder.source("PLATFORM");
            builder.prompt(request.getPrompt())
                    .cwd(request.getCwd())
                    .model(request.getModel());
        }

        // 从 artifacts 提取 resultText
        if (a2aTask.getArtifacts() != null && !a2aTask.getArtifacts().isEmpty()) {
            a2aTask.getArtifacts().stream()
                    .flatMap(art -> art.getParts() != null ? art.getParts().stream() : java.util.stream.Stream.empty())
                    .filter(p -> "text".equals(p.getType()) && p.getText() != null)
                    .findFirst()
                    .ifPresent(p -> builder.resultText(p.getText()));
        }

        // 错误信息
        if (a2aTask.getStatus() != null && a2aTask.getStatus().getDescription() != null) {
            A2aTaskState state = a2aTask.getStatus().getState();
            if (state == A2aTaskState.FAILED) {
                builder.errorMessage(a2aTask.getStatus().getDescription());
            }
        }

        return builder.build();
    }

    private String mapA2aState(A2aTaskState state) {
        if (state == null) return "PENDING";
        return switch (state) {
            case SUBMITTED -> "PENDING";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "ABORTED";
        };
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private int computeFetchSize(int page, int size) {
        return Math.max(size, (page + 1) * size);
    }

    private Map<String, Object> buildTaskPageResponse(List<Object> taskItems, long totalSessions, int page, int size) {
        Map<String, List<Object>> sessions = new LinkedHashMap<>();
        for (Object item : taskItems) {
            String sessionId = readStringProperty(item, "sessionId");
            String key = (sessionId != null && !sessionId.isBlank())
                    ? sessionId
                    : Optional.ofNullable(readStringProperty(item, "taskId")).orElse(UUID.randomUUID().toString());
            sessions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }

        List<List<Object>> sortedSessions = new ArrayList<>(sessions.values());
        sortedSessions.sort((left, right) -> compareNullableTime(
                latestTaskTime(right),
                latestTaskTime(left)));

        int from = Math.min(page * size, sortedSessions.size());
        int to = Math.min(from + size, sortedSessions.size());
        List<Object> content = sortedSessions.subList(from, to).stream()
                .flatMap(Collection::stream)
                .toList();

        return Map.of(
                "content", content,
                "totalSessions", totalSessions,
                "page", page,
                "size", size
        );
    }

    private LocalDateTime latestTaskTime(List<Object> tasks) {
        return tasks.stream()
                .map(task -> {
                    LocalDateTime createdAt = readDateTimeProperty(task, "createdAt");
                    LocalDateTime updatedAt = readDateTimeProperty(task, "updatedAt");
                    return compareNullableTime(createdAt, updatedAt) >= 0 ? createdAt : updatedAt;
                })
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private TaskPageEnvelope toTaskPageEnvelope(Object pageResult) {
        return new TaskPageEnvelope(
                readListProperty(pageResult, "content"),
                readLongProperty(pageResult, "totalSessions")
        );
    }

    private SearchEnvelope toSearchEnvelope(Object searchResult) {
        return new SearchEnvelope(
                readListProperty(searchResult, "results"),
                readLongProperty(searchResult, "total")
        );
    }

    private List<Object> readListProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    private long readLongProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String readStringProperty(Object target, String property) {
        Object value = readProperty(target, property);
        return value != null ? value.toString() : null;
    }

    private LocalDateTime readDateTimeProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime();
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.parse(text);
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private Object readProperty(Object target, String property) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(property);
        }
        try {
            Method getter = target.getClass().getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            return getter.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
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

    private Object listTasksPagedFromSessionStore(String userId, String directoryId, int page, int size, String state) {
        List<SessionTaskEntity> tasks = directoryId == null || directoryId.isBlank()
                ? sessionTaskRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        if (tasks.isEmpty()) {
            return null;
        }

        List<UnifiedSessionView> sessions = buildUnifiedSessionViews(tasks, userId, directoryId, state);
        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<SessionTaskEntity> pagedTasks = sessions.subList(from, to).stream()
                .flatMap(view -> view.tasks().stream())
                .toList();
        List<Object> content = new ArrayList<>(toDispatchTaskDTOs(pagedTasks));

        return Map.of(
                "content", content,
                "totalSessions", (long) sessions.size(),
                "page", page,
                "size", size
        );
    }

    private Object searchSessionsFromSessionStore(String userId, String keyword, String workerId,
                                                  String directoryId, int page, int size) {
        List<SessionTaskEntity> tasks = sessionTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (tasks.isEmpty()) {
            return null;
        }

        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        List<UnifiedSessionView> sessions = buildUnifiedSessionViews(tasks, userId, directoryId, null).stream()
                .filter(view -> matchesWorkerFilter(view, workerId))
                .filter(view -> matchesKeywordFilter(view, normalizedKeyword))
                .toList();

        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<Map<String, Object>> results = sessions.subList(from, to).stream()
                .map(this::toSearchResult)
                .toList();

        return Map.of(
                "results", results,
                "total", (long) sessions.size(),
                "page", page,
                "size", size
        );
    }

    private List<UnifiedSessionView> buildUnifiedSessionViews(List<SessionTaskEntity> tasks, String userId,
                                                              String directoryId, String state) {
        Map<String, List<SessionTaskEntity>> grouped = groupSessionTasks(tasks);
        Map<String, SessionEntity> sessionsById = loadSessions(grouped.keySet());
        Set<String> stateFilter = parseInteractionStates(state);

        return grouped.entrySet().stream()
                .map(entry -> toUnifiedSessionView(entry.getKey(), entry.getValue(), sessionsById.get(entry.getKey())))
                .filter(view -> view.session() == null || view.session().getDeletedAt() == null)
                .filter(view -> view.session() == null || userId.equals(view.session().getUserId()))
                .filter(view -> matchesDirectoryFilter(view, directoryId))
                .filter(view -> stateFilter.isEmpty() || stateFilter.contains(resolveInteractionState(view)))
                .sorted((left, right) -> compareNullableTime(resolveSessionSortTime(right), resolveSessionSortTime(left)))
                .toList();
    }

    private Map<String, List<SessionTaskEntity>> groupSessionTasks(List<SessionTaskEntity> tasks) {
        Map<String, List<SessionTaskEntity>> grouped = new LinkedHashMap<>();
        for (SessionTaskEntity task : tasks) {
            String key = task.getSessionId() != null && !task.getSessionId().isBlank()
                    ? task.getSessionId()
                    : "task:" + task.getTaskId();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
        }
        grouped.values().forEach(group -> group.sort((left, right) -> compareNullableTime(
                firstNonNull(right.getCreatedAt(), right.getUpdatedAt()),
                firstNonNull(left.getCreatedAt(), left.getUpdatedAt()))));
        return grouped;
    }

    private Map<String, SessionEntity> loadSessions(Collection<String> sessionIds) {
        List<String> persistedSessionIds = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank() && !id.startsWith("task:"))
                .toList();
        if (persistedSessionIds.isEmpty()) {
            return Map.of();
        }
        return sessionRepository.findAllById(persistedSessionIds).stream()
                .collect(Collectors.toMap(SessionEntity::getId, session -> session));
    }

    private UnifiedSessionView toUnifiedSessionView(String sessionKey, List<SessionTaskEntity> tasks, SessionEntity session) {
        SessionTaskEntity latestTask = tasks.get(0);
        SessionTaskEntity earliestTask = tasks.get(tasks.size() - 1);
        return new UnifiedSessionView(sessionKey, session, tasks, latestTask, earliestTask);
    }

    private boolean matchesWorkerFilter(UnifiedSessionView view, String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return true;
        }
        String currentWorkerId = firstNonBlank(
                view.latestTask().getWorkerId(),
                view.session() != null ? view.session().getCurrentWorkerId() : null
        );
        return workerId.equals(currentWorkerId);
    }

    private boolean matchesDirectoryFilter(UnifiedSessionView view, String directoryId) {
        if (directoryId == null || directoryId.isBlank()) {
            return true;
        }
        String currentDirectoryId = firstNonBlank(
                view.latestTask().getDirectoryId(),
                view.session() != null ? view.session().getCurrentDirectoryId() : null
        );
        return directoryId.equals(currentDirectoryId);
    }

    private boolean matchesKeywordFilter(UnifiedSessionView view, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        if (view.session() != null) {
            if (containsIgnoreCase(view.session().getTitle(), keyword)
                    || containsIgnoreCase(view.session().getTagsJson(), keyword)) {
                return true;
            }
        }
        return view.tasks().stream().anyMatch(task ->
                containsIgnoreCase(task.getPrompt(), keyword)
                        || containsIgnoreCase(task.getResultText(), keyword));
    }

    private Map<String, Object> toSearchResult(UnifiedSessionView view) {
        BigDecimal totalCost = view.tasks().stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", firstNonBlank(view.latestTask().getSessionId(), view.sessionKey()));
        result.put("workerId", firstNonBlank(view.latestTask().getWorkerId(),
                view.session() != null ? view.session().getCurrentWorkerId() : null));
        result.put("directoryId", firstNonBlank(view.latestTask().getDirectoryId(),
                view.session() != null ? view.session().getCurrentDirectoryId() : null));
        result.put("firstPrompt", truncate(view.earliestTask().getPrompt(), 200));
        result.put("customTitle", view.session() != null ? view.session().getTitle() : null);
        result.put("tags", view.session() != null ? parseTags(view.session().getTagsJson()) : List.of());
        result.put("interactionState", resolveInteractionState(view));
        result.put("latestTaskId", view.latestTask().getTaskId());
        result.put("latestStatus", view.latestTask().getStatus());
        result.put("model", firstNonBlank(view.latestTask().getModel(),
                view.session() != null ? view.session().getLatestModel() : null));
        result.put("cwd", view.latestTask().getCwd());
        result.put("source", view.latestTask().getSource());
        result.put("totalCost", totalCost);
        result.put("createdAt", view.earliestTask().getCreatedAt());
        result.put("updatedAt", resolveSessionSortTime(view));
        return result;
    }

    private List<DispatchTaskDTO> toDispatchTaskDTOs(List<SessionTaskEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Map<String, String> directoryNames = loadDirectoryNames(entities);
        return entities.stream()
                .map(entity -> toDispatchTaskDTO(entity, directoryNames))
                .toList();
    }

    private DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity) {
        return toDispatchTaskDTO(entity, loadDirectoryNames(List.of(entity)));
    }

    private DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity, Map<String, String> directoryNames) {
        Map<String, Object> state = parseJsonObject(entity.getTaskStateJson());
        DispatchTaskDTO.DispatchTaskDTOBuilder builder = DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getProviderTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .providerType(entity.getProviderType())
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
                .directoryName(directoryNames.get(entity.getDirectoryId()))
                .claudeSessionId(asString(state.get("claudeSessionId")))
                .codexThreadId(asString(state.get("codexThreadId")))
                .contextId(asString(state.get("contextId")))
                .fileCheckpointingEnabled(asBoolean(state.get("fileCheckpointingEnabled")));
        if (state.containsKey("checkpoints")) {
            builder.checkpoints(writeJson(state.get("checkpoints")));
        }
        return builder.build();
    }

    private Map<String, String> loadDirectoryNames(List<SessionTaskEntity> entities) {
        if (workingDirectoryRepository == null || entities == null || entities.isEmpty()) {
            return Map.of();
        }
        List<String> directoryIds = entities.stream()
                .map(SessionTaskEntity::getDirectoryId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (directoryIds.isEmpty()) {
            return Map.of();
        }
        List<com.foggy.navigator.common.entity.WorkingDirectoryEntity> directories =
                workingDirectoryRepository.findByDirectoryIdIn(directoryIds);
        if (directories == null || directories.isEmpty()) {
            return Map.of();
        }
        return directories.stream()
                .collect(Collectors.toMap(
                        com.foggy.navigator.common.entity.WorkingDirectoryEntity::getDirectoryId,
                        com.foggy.navigator.common.entity.WorkingDirectoryEntity::getProjectName,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse task/session JSON payload: {}", json);
            return Map.of();
        }
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse session tags JSON: {}", tagsJson);
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task state payload", e);
        }
    }

    private Set<String> parseInteractionStates(String interactionState) {
        if (interactionState == null || interactionState.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(interactionState.split(","))
                .map(String::trim)
                .filter(state -> !state.isEmpty())
                .collect(Collectors.toSet());
    }

    private String resolveInteractionState(UnifiedSessionView view) {
        if (view.session() != null && view.session().getInteractionState() != null
                && !view.session().getInteractionState().isBlank()) {
            return view.session().getInteractionState();
        }
        return deriveInteractionState(view.latestTask().getStatus());
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

    private LocalDateTime resolveSessionSortTime(UnifiedSessionView view) {
        if (view.session() != null) {
            LocalDateTime sessionTime = firstNonNull(view.session().getLastActivityAt(), view.session().getUpdatedAt());
            if (sessionTime != null) {
                return sessionTime;
            }
        }
        return firstNonNull(view.latestTask().getUpdatedAt(), view.latestTask().getCreatedAt());
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

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
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

    /**
     * 路由决策核心 —— 根据 agentId 格式决定路由路径：
     * <ol>
     *   <li>"directory#xxx" → Direct Provider Route（隐式目录 Agent，providerType 从 modelConfigId 推导）</li>
     *   <li>真实 agentId → A2A Route（通过 UnifiedAgentResolver 解析）</li>
     *   <li>无 agentId → 尝试 session 绑定的 agentId，或 fallback 到 Direct Provider Route</li>
     * </ol>
     */
    private CreateExecutionTarget resolveCreateExecutionTarget(TaskDispatchRequest request) {
        String agentId = request.getAgentId();

        // Case 1: directory# 隐式 Agent → Direct Provider Route
        if (agentId != null && DirectoryAgentId.isDirectoryAgent(agentId)) {
            return resolveDirectoryAgentTarget(request, agentId);
        }

        // Case 2: 真实 agentId → A2A Route
        if (agentId != null && !agentId.isBlank()) {
            return CreateExecutionTarget.a2a(new AgentLookup(agentId, "EXPLICIT_AGENT"));
        }

        // Case 3: 无 agentId → 尝试 session 绑定
        String sessionAgentId = resolveBoundOrExplicitAgentId(null, request.getSessionId());
        if (sessionAgentId != null && !sessionAgentId.isBlank()) {
            if (DirectoryAgentId.isDirectoryAgent(sessionAgentId)) {
                return resolveDirectoryAgentTarget(request, sessionAgentId);
            }
            return CreateExecutionTarget.a2a(new AgentLookup(sessionAgentId, "SESSION_AGENT"));
        }

        // Case 4: 完全无 Agent → 通过 modelConfigId 推导 providerType，走 Direct Provider Route（向后兼容 ad-hoc cwd）
        String providerType = resolveProviderTypeFromModelConfig(request.getModelConfigId());
        if (providerType != null && findTaskQueryProviderByType(providerType).isPresent()) {
            return CreateExecutionTarget.direct(providerType);
        }

        throw new IllegalArgumentException("无法确定执行后端：请指定 agentId 或 modelConfigId");
    }

    /**
     * directory# 隐式 Agent 路由：从目录解析 modelConfigId，推导 providerType。
     * 同时将解析出的 directoryId 回填到 request（供 provider 使用）。
     */
    private CreateExecutionTarget resolveDirectoryAgentTarget(TaskDispatchRequest request, String directoryAgentId) {
        String directoryId = DirectoryAgentId.extractDirectoryId(directoryAgentId);

        // 回填 directoryId（如果 request 中未设置）
        if (request.getDirectoryId() == null || request.getDirectoryId().isBlank()) {
            request.setDirectoryId(directoryId);
        }

        // modelConfigId 解析：显式传入 > 目录默认
        String modelConfigId = resolveModelConfigIdFromDirectory(request.getModelConfigId(), directoryId);
        if (modelConfigId == null) {
            throw new IllegalArgumentException("该工作目录需要配置 LLM 模型才能执行任务（directoryId=" + directoryId + "）");
        }
        // 将解析后的 modelConfigId 回填到 request，确保 provider 拿到有效值
        request.setModelConfigId(modelConfigId);

        String providerType = resolveProviderTypeFromModelConfig(modelConfigId);
        if (providerType == null) {
            throw new IllegalArgumentException("modelConfigId " + modelConfigId + " 无法推导执行后端类型");
        }
        return CreateExecutionTarget.direct(providerType);
    }

    /**
     * 从目录解析 modelConfigId：显式传入优先，fallback 到目录默认配置。
     */
    private String resolveModelConfigIdFromDirectory(String explicitModelConfigId, String directoryId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return explicitModelConfigId;
        }
        if (workingDirectoryRepository != null && directoryId != null && !directoryId.isBlank()) {
            return workingDirectoryRepository.findByDirectoryId(directoryId)
                    .map(com.foggy.navigator.common.entity.WorkingDirectoryEntity::getDefaultModelConfigId)
                    .filter(id -> !id.isBlank())
                    .orElse(null);
        }
        return null;
    }

    private DispatchTaskDTO createTaskDirect(String providerType, TaskDispatchRequest request, AgentResolveContext context) {
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        TaskQueryProvider provider = findTaskQueryProviderByType(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Provider not available: " + providerType));
        Map<String, Object> params = buildDirectCreateParams(request);
        DispatchTaskDTO dto = provider.createTaskDirect(params, context.getUserId(), context.getTenantId());
        log.info("Dispatched task directly via provider: providerType={}, taskId={}, workerId={}, directoryId={}",
                providerType, dto.getTaskId(), request.getWorkerId(), request.getDirectoryId());
        return dto;
    }

    /**
     * Request → Map 公共转换：提取所有标准字段到 Map（供 Direct/Resume/A2A 各路径复用）。
     */
    @SuppressWarnings("deprecation")
    private Map<String, Object> toCommonParams(TaskDispatchRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotBlank(params, "agentId", request.getAgentId());
        putIfNotBlank(params, "providerType", request.getProviderType());
        putIfNotBlank(params, "sessionId", request.getSessionId());
        putIfNotBlank(params, "workerId", request.getWorkerId());
        putIfNotBlank(params, "prompt", request.getPrompt());
        putIfNotBlank(params, "cwd", request.getCwd());
        putIfNotBlank(params, "directoryId", request.getDirectoryId());
        putIfNotBlank(params, "model", request.getModel());
        putIfNotBlank(params, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(params, "permissionMode", request.getPermissionMode());
        putIfNotBlank(params, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(params, "agentTeamsJson", request.getAgentTeamsJson());
        // claudeSessionId / codexThreadId 不再透传 — Provider 从 SessionEntity.providerStateJson 恢复
        if (request.getMaxTurns() != null) {
            params.put("maxTurns", request.getMaxTurns());
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            params.put("images", String.join(",", request.getImages()));
        }
        return params;
    }

    private Map<String, Object> buildDirectCreateParams(TaskDispatchRequest request) {
        return toCommonParams(request);
    }

    private String mapWorkerBackendToProviderType(String workerBackend) {
        if (workerBackend == null || workerBackend.isBlank()) {
            return null;
        }
        return switch (workerBackend) {
            case "OPENAI_CODEX" -> "codex-worker";
            case "CLAUDE_CODE" -> "claude-worker";
            default -> null;
        };
    }

    private String resolveBoundOrExplicitAgentId(@Nullable String requestedAgentId, @Nullable String sessionId) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionRepository.findById(sessionId)
                .map(SessionEntity::getAgentId)
                .filter(agentId -> !agentId.isBlank())
                .orElse(null);
    }

    private String resolveProviderTypeFromModelConfig(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .map(cfg -> mapWorkerBackendToProviderType(cfg.getWorkerBackend()))
                .orElse(null);
    }

    /**
     * Resume 上下文规范化 —— session 已绑定 provider 时，
     * 将 request 中与 session provider 不兼容的字段静默修正，
     * 避免前端传入的 modelConfigId / providerType 与 session 绑定冲突。
     * <p>
     * 仅在 resume 路径调用；createTask 首次绑定时保留硬校验。
     */
    private void normalizeResumeRequest(TaskDispatchRequest request, String resolvedProviderType) {
        if (resolvedProviderType == null || resolvedProviderType.isBlank()) {
            return;
        }

        // 1. providerType 对齐
        String reqProvider = request.getProviderType();
        if (reqProvider != null && !reqProvider.isBlank() && !resolvedProviderType.equals(reqProvider)) {
            log.info("Resume normalize: overriding providerType {} → {} (session-bound)",
                    reqProvider, resolvedProviderType);
            request.setProviderType(resolvedProviderType);
        }

        // 2. modelConfigId 兼容性检查，不兼容则清空（让 provider 内部用自己的默认配置）
        String modelConfigId = request.getModelConfigId();
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            String modelProviderType = resolveProviderTypeFromModelConfig(modelConfigId);
            if (modelProviderType != null && !resolvedProviderType.equals(modelProviderType)) {
                log.info("Resume normalize: clearing modelConfigId {} (targets {}, session bound to {})",
                        modelConfigId, modelProviderType, resolvedProviderType);
                request.setModelConfigId(null);
            }
        }
    }

    private void validateRequestedProviderTypeCompatibility(String requestedProviderType, String resolvedProviderType) {
        if (requestedProviderType == null || requestedProviderType.isBlank()
                || resolvedProviderType == null || resolvedProviderType.isBlank()) {
            return;
        }

        if (!resolvedProviderType.equals(requestedProviderType)) {
            throw new IllegalArgumentException(
                    "providerType " + requestedProviderType + " conflicts with resolved provider " + resolvedProviderType);
        }
    }

    private void validateModelConfigProviderCompatibility(String modelConfigId, String providerType) {
        if (modelConfigId == null || modelConfigId.isBlank() || providerType == null || providerType.isBlank()) {
            return;
        }

        String modelProviderType = resolveProviderTypeFromModelConfig(modelConfigId);
        if (modelProviderType == null || modelProviderType.isBlank()) {
            return;
        }

        if (!providerType.equals(modelProviderType)) {
            throw new IllegalArgumentException(
                    "modelConfigId " + modelConfigId + " targets provider " + modelProviderType
                            + ", but resolved provider is " + providerType);
        }
    }

    private String resolveLogicalAgentId(A2aAgent agent, String lookupId) {
        if (agent.getAgentCard() != null
                && agent.getAgentCard().getId() != null
                && !agent.getAgentCard().getId().isBlank()) {
            return agent.getAgentCard().getId();
        }
        return lookupId;
    }

    private static final class AgentLookup {
        private final String lookupId;
        private final String bindingSource;

        private AgentLookup(String lookupId, String bindingSource) {
            this.lookupId = lookupId;
            this.bindingSource = bindingSource;
        }
    }

    private record CreateExecutionTarget(@Nullable String providerType,
                                         @Nullable AgentLookup agentLookup,
                                         boolean directProviderRoute) {

        private static CreateExecutionTarget direct(String providerType) {
            return new CreateExecutionTarget(providerType, null, true);
        }

        private static CreateExecutionTarget a2a(AgentLookup agentLookup) {
            return new CreateExecutionTarget(null, agentLookup, false);
        }
    }

    private record TaskPageEnvelope(List<Object> content, long totalSessions) {
    }

    private record SearchEnvelope(List<Object> results, long total) {
    }

    private record UnifiedSessionView(String sessionKey,
                                      SessionEntity session,
                                      List<SessionTaskEntity> tasks,
                                      SessionTaskEntity latestTask,
                                      SessionTaskEntity earliestTask) {
    }

}

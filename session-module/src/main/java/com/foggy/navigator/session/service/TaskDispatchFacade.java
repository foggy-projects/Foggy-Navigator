package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.*;
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
        DispatchTaskDTO directTask = tryCreateTaskDirect(request, context);
        if (directTask != null) {
            return directTask;
        }

        AgentLookup lookup = resolveAgentLookup(request);

        A2aAgent agent = agentResolver.resolveAgent(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + lookup.lookupId));

        String providerType = agentResolver.getProviderType(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + lookup.lookupId));

        String agentId = resolveLogicalAgentId(agent, lookup.lookupId);

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
        // resume: 尝试多个标识符解析 provider（directoryId 可能未绑定，需 fallback 到 workerId）
        String providerType = resolveProviderTypeWithFallback(request, context);

        // 找到对应 Provider 直接调用 resumeTask
        TaskQueryProvider provider = taskQueryProviders.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));

        Map<String, Object> params = buildResumeParams(request);
        return provider.resumeTask(context.getUserId(), context.getTenantId(), params);
    }

    /**
     * 带 fallback 的 provider 类型解析。
     * 依次尝试 agentId → directoryId → workerId，第一个命中即返回。
     * 适用于 resume 场景：directoryId 未绑定时可用 workerId 兜底。
     */
    private String resolveProviderTypeWithFallback(TaskDispatchRequest request, AgentResolveContext context) {
        // 按优先级构造候选 lookupId 列表
        String[] candidates = {
                request.getAgentId(),
                request.getDirectoryId(),
                request.getWorkerId()
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Optional<String> pt = agentResolver.getProviderType(candidate, context);
                if (pt.isPresent()) {
                    return pt.get();
                }
            }
        }
        throw new IllegalArgumentException("No provider found for resume request (tried agentId/directoryId/workerId)");
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId, String userId) {
        TaskQueryProvider provider = findProviderForTask(taskId);
        provider.deleteTask(userId, taskId);
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
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotBlank(params, "workerId", request.getWorkerId());
        putIfNotBlank(params, "claudeSessionId", request.getClaudeSessionId());
        putIfNotBlank(params, "prompt", request.getPrompt());
        putIfNotBlank(params, "cwd", request.getCwd());
        putIfNotBlank(params, "directoryId", request.getDirectoryId());
        putIfNotBlank(params, "sessionId", request.getSessionId());
        putIfNotBlank(params, "model", request.getModel());
        putIfNotBlank(params, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(params, "permissionMode", request.getPermissionMode());
        putIfNotBlank(params, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(params, "agentTeamsJson", request.getAgentTeamsJson());
        putIfNotBlank(params, "codexThreadId", request.getCodexThreadId());
        if (request.getMaxTurns() != null) params.put("maxTurns", request.getMaxTurns());
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            params.put("images", String.join(",", request.getImages()));
        }
        return params;
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
                return taskQueryProviders.stream()
                        .filter(provider -> providerType.equals(provider.getProviderType()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));
            }
        }

        for (TaskQueryProvider p : taskQueryProviders) {
            if (p.getTaskById(taskId).isPresent()) return p;
        }
        throw new IllegalArgumentException("Task not found: " + taskId);
    }

    // ── 内部方法 ──

    private A2aMessage buildMessage(TaskDispatchRequest request) {
        List<A2aPart> parts = new ArrayList<>();
        parts.add(A2aPart.text(request.getPrompt()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "sessionId", request.getSessionId());
        putIfNotBlank(metadata, "workerId", request.getWorkerId());
        putIfNotBlank(metadata, "cwd", request.getCwd());
        putIfNotBlank(metadata, "directoryId", request.getDirectoryId());
        putIfNotBlank(metadata, "model", request.getModel());
        putIfNotBlank(metadata, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(metadata, "permissionMode", request.getPermissionMode());
        putIfNotBlank(metadata, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(metadata, "agentTeamsJson", request.getAgentTeamsJson());
        putIfNotBlank(metadata, "codexThreadId", request.getCodexThreadId());
        if (request.getMaxTurns() != null) {
            metadata.put("maxTurns", request.getMaxTurns());
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            metadata.put("images", request.getImages());
        }

        return A2aMessage.builder()
                .role("user")
                .parts(parts)
                .contextId(request.getContextId())
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

        // 从 request 补充（如果 metadata 里没有）
        if (request != null) {
            DispatchTaskDTO dto = builder.build();
            if (dto.getSessionId() == null) builder.sessionId(request.getSessionId());
            if (dto.getWorkerId() == null) builder.workerId(request.getWorkerId());
            if (dto.getDirectoryId() == null) builder.directoryId(request.getDirectoryId());
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
                .agentId(entity.getAgentId() != null ? entity.getAgentId() : entity.getProviderType())
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

    private AgentLookup resolveAgentLookup(TaskDispatchRequest request) {
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            return new AgentLookup(request.getAgentId(), "EXPLICIT_AGENT");
        }
        if (request.getDirectoryId() != null && !request.getDirectoryId().isBlank()) {
            return new AgentLookup(request.getDirectoryId(), "DIRECTORY_ID");
        }
        if (request.getWorkerId() != null && !request.getWorkerId().isBlank()) {
            return new AgentLookup(request.getWorkerId(), "WORKER_ID");
        }
        throw new IllegalArgumentException("agentId, directoryId or workerId is required");
    }

    private DispatchTaskDTO tryCreateTaskDirect(TaskDispatchRequest request, AgentResolveContext context) {
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            return null;
        }
        if (request.getWorkerId() == null || request.getWorkerId().isBlank()) {
            return null;
        }
        if (request.getModelConfigId() == null || request.getModelConfigId().isBlank()) {
            return null;
        }

        String providerType = llmModelManager.getModelConfig(request.getModelConfigId())
                .map(cfg -> mapWorkerBackendToProviderType(cfg.getWorkerBackend()))
                .orElse(null);
        if (providerType == null || providerType.isBlank()) {
            return null;
        }

        // claude-worker 有完整 A2a 解析链，带 sessionId 时优先走绑定路径
        if ("claude-worker".equals(providerType)
                && request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return null;
        }

        TaskQueryProvider provider = taskQueryProviders.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            log.warn("Direct task route skipped: provider {} not available for modelConfigId={}",
                    providerType, request.getModelConfigId());
            return null;
        }

        Map<String, Object> params = buildDirectCreateParams(request);
        DispatchTaskDTO dto = provider.createTaskDirect(params, context.getUserId(), context.getTenantId());
        log.info("Dispatched task directly via provider: providerType={}, taskId={}, workerId={}, directoryId={}",
                providerType, dto.getTaskId(), request.getWorkerId(), request.getDirectoryId());
        return dto;
    }

    private Map<String, Object> buildDirectCreateParams(TaskDispatchRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfNotBlank(params, "workerId", request.getWorkerId());
        putIfNotBlank(params, "prompt", request.getPrompt());
        putIfNotBlank(params, "cwd", request.getCwd());
        putIfNotBlank(params, "directoryId", request.getDirectoryId());
        putIfNotBlank(params, "model", request.getModel());
        putIfNotBlank(params, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(params, "permissionMode", request.getPermissionMode());
        putIfNotBlank(params, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(params, "agentTeamsJson", request.getAgentTeamsJson());
        putIfNotBlank(params, "codexThreadId", request.getCodexThreadId());
        if (request.getMaxTurns() != null) {
            params.put("maxTurns", request.getMaxTurns());
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            params.put("images", String.join(",", request.getImages()));
        }
        return params;
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

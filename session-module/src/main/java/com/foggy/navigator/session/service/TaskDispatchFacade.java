package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

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
public class TaskDispatchFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final LlmModelManager llmModelManager;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private WorkingDirectoryRepository workingDirectoryRepository;

    @Autowired(required = false)
    @Nullable
    private AgentConversationContextRepository agentConversationContextRepository;

    public TaskDispatchFacade(UnifiedAgentResolver agentResolver,
                              SessionBindingService bindingService,
                              SessionRepository sessionRepository,
                              List<? extends TaskLookupProvider> taskLookupProviders,
                              List<? extends TaskCommandProvider> taskCommandProviders,
                              List<? extends TaskListingProvider> taskListingProviders,
                              List<? extends WorkerSessionQueryProvider> workerSessionQueryProviders,
                              LlmModelManager llmModelManager) {
        this.agentResolver = agentResolver;
        this.bindingService = bindingService;
        this.sessionRepository = sessionRepository;
        this.taskQueryProviderRegistry = new TaskQueryProviderRegistry(
                taskLookupProviders,
                taskCommandProviders,
                taskListingProviders,
                workerSessionQueryProviders);
        this.llmModelManager = llmModelManager;
    }

    private TaskCreateTargetResolver createTargetResolver() {
        return new TaskCreateTargetResolver(
                sessionRepository,
                workingDirectoryRepository,
                taskQueryProviderRegistry,
                llmModelManager);
    }

    private UnifiedSessionTaskProjectionService projectionService() {
        return new UnifiedSessionTaskProjectionService(sessionRepository, workingDirectoryRepository);
    }

    private TaskOperationRouter operationRouter() {
        return new TaskOperationRouter(
                agentResolver,
                bindingService,
                sessionRepository,
                sessionTaskRepository,
                taskQueryProviderRegistry,
                createTargetResolver(),
                projectionService());
    }

    /**
     * 创建任务。
     * <p>
     * 1. 解析 Agent 查找键（显式 agentId → directoryId → workerId）
     * 2. 验证/建立 Session ↔ Agent 绑定
     * 3. 构造 A2aMessage，调用 A2aAgent.sendTask()
     * 4. 返回统一 DTO
     */
    public DispatchTaskDTO createTask(TaskDispatchRequest request, AgentResolveContext context) {
        validateContextBindingBeforeDispatch(request, context);
        if (bindContinuationFromContext(request, context)) {
            return resumeTask(request, context);
        }

        TaskCreateTargetResolver.CreateExecutionTarget target = createTargetResolver().resolveCreateExecutionTarget(request);
        if (target.directProviderRoute()) {
            return createTaskDirect(target.providerType(), request, context);
        }

        TaskCreateTargetResolver.AgentLookup lookup = target.agentLookup();

        A2aAgent agent = agentResolver.resolveAgent(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + lookup.lookupId));

        String providerType = agentResolver.getProviderType(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + lookup.lookupId));

        String agentId = resolveLogicalAgentId(agent, lookup.lookupId);
        TaskOperationRouter operations = operationRouter();
        operations.validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        operations.validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

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

        DispatchTaskDTO dto = toDispatchDTO(a2aTask, agentId, providerType, request);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, providerType);
        return dto;
    }

    /**
     * Submit an application-level Agent task and return the A2A task view.
     * <p>
     * This is the implementation backing {@code TaskSubmittingA2aAgent}. It
     * intentionally delegates to {@link #createTask(TaskDispatchRequest, AgentResolveContext)}
     * so complex entry points share the same session projection, provider routing
     * and task persistence path.
     */
    public A2aTask submitTask(AgentTaskSubmitRequest request) {
        return toA2aTask(submitTaskDispatch(request));
    }

    public DispatchTaskDTO submitTaskDispatch(AgentTaskSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("submit request is required");
        }
        AgentResolveContext context = request.getResolveContext();
        if (context == null) {
            context = AgentResolveContext.builder().requestSource("A2A_SUBMIT").build();
        }
        return createTask(toTaskDispatchRequest(request), context);
    }

    /**
     * 查询单个任务（遍历所有 lookup provider）
     */
    public Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
        return operationRouter().getTask(taskId, context);
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
        if (providerType != null && !providerType.isBlank()) {
            // 精确匹配 provider
            return taskQueryProviderRegistry.findLookupProviderByType(providerType)
                    .map(p -> p.listTasksBySession(sessionId))
                    .orElse(List.of());
        }

        // providerType 为空（旧会话），遍历所有 provider
        return taskQueryProviderRegistry.lookupProviders().stream()
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

        return taskQueryProviderRegistry.lookupProviders().stream()
                .flatMap(p -> p.listActiveDispatchTasks(userId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 取消任务。
     */
    public void cancelTask(String taskId, String agentId, AgentResolveContext context) {
        operationRouter().cancelTask(taskId, agentId, context);
    }

    // ── 任务操作（路由到 command provider） ──

    /**
     * 回复权限请求 / 用户问题（不支持的 Provider 自动抛 UnsupportedOperationException）
     */
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        operationRouter().respondToTask(taskId, userId, response);
    }

    /**
     * 重连任务 SSE 流
     */
    public void reconnectTask(String taskId, String userId) {
        operationRouter().reconnectTask(taskId, userId);
    }

    /**
     * 重新同步任务状态
     */
    public Object resyncTask(String taskId, String userId) {
        return operationRouter().resyncTask(taskId, userId);
    }

    /**
     * 回退到检查点
     */
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        return operationRouter().rewindTask(taskId, userId, params);
    }

    // ── Phase 3: 统一任务端点扩展 ──

    /**
     * 恢复任务（resume）—— 续接已有会话。
     */
    public DispatchTaskDTO resumeTask(TaskDispatchRequest request, AgentResolveContext context) {
        DispatchTaskDTO dto = operationRouter().resumeTask(request, context);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, dto.getProviderType());
        return dto;
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId, String userId) {
        operationRouter().deleteTask(taskId, userId);
    }

    /**
     * 扫描 checkpoints
     */
    public Object scanCheckpoints(String taskId, String userId) {
        return operationRouter().scanCheckpoints(taskId, userId);
    }

    /**
     * 分页查询任务列表（按会话聚合所有 Provider，再统一分页）
     */
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTasksPaged(userId, page, size, state, false);
    }

    /**
     * 分页查询任务列表（按会话聚合所有 Provider，再统一分页）
     */
    public Object listTasksPaged(String userId, int page, int size, String state, boolean compact) {
        if (sessionTaskRepository != null) {
            Object unified = listTasksPagedFromSessionStore(userId, null, page, size, state, compact);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_PAGED)) {
            try {
                TaskPageResult pageResult = provider.listTaskPage(userId, 0, fetchSize, state);
                UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(compact
                        ? envelope.content().stream().map(this::toCompactTaskItem).toList()
                        : envelope.content());
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

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.SEARCH_SESSIONS)) {
            try {
                TaskSearchResult searchResult = provider.searchSessionPage(userId, keyword, workerId, directoryId, 0, fetchSize);
                UnifiedSessionTaskProjectionService.SearchEnvelope envelope = toSearchEnvelope(searchResult);
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

        return taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_BY_DIRECTORY).stream()
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
            Object unified = listTasksPagedFromSessionStore(userId, directoryId, page, size, state, false);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_BY_DIRECTORY_PAGED)) {
            try {
                TaskPageResult pageResult = provider.listDirectoryTaskPage(userId, directoryId, 0, fetchSize, state);
                UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(envelope.content());
                totalSessions += envelope.totalSessions();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        return buildTaskPageResponse(content, totalSessions, page, size);
    }

    // ── Worker Session 查询（统一端点） ──

    public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.LIST_WORKER_SESSIONS)) {
            try {
                List<WorkerSessionSummary> sessions = provider.listWorkerSessionSummaries(workerId, userId);
                return sessions != null ? sessions : List.of();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return List.of();
    }

    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        return listWorkerSessionSummaries(workerId, userId).stream()
                .map(WorkerSessionSummary::toMap)
                .toList();
    }

    public WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId, String userId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT)) {
            try {
                WorkerSessionMessageCount count = provider.getWorkerSessionMessageCountResult(workerId, sessionId, userId);
                return count != null ? count : WorkerSessionMessageCount.empty();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return WorkerSessionMessageCount.empty();
    }

    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        return getWorkerSessionMessageCountResult(workerId, sessionId, userId).toMap();
    }

    public List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                String userId, Integer offset, Integer limit) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.GET_WORKER_SESSION_MESSAGES)) {
            try {
                List<WorkerSessionMessage> messages = provider.listWorkerSessionMessages(workerId, sessionId, userId, offset, limit);
                return messages != null ? messages : List.of();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return List.of();
    }

    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        return listWorkerSessionMessages(workerId, sessionId, userId, offset, limit).stream()
                .map(WorkerSessionMessage::toMap)
                .toList();
    }

    public WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.SYNC_WORKER_SESSIONS)) {
            try {
                WorkerSessionSyncResult result = provider.syncWorkerSessionState(workerId, userId, tenantId);
                return result != null ? result : WorkerSessionSyncResult.of(0, 0);
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        throw new UnsupportedOperationException("No provider supports syncWorkerSessions");
    }

    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        return syncWorkerSessionState(workerId, userId, tenantId).toMap();
    }

    private boolean isWorkerNotFound(IllegalArgumentException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("worker not found");
    }

    // ── 内部方法 ──

    private A2aMessage buildMessage(TaskDispatchRequest request) {
        List<A2aPart> parts = new ArrayList<>();
        parts.add(A2aPart.text(request.getPrompt()));

        // 复用公共参数转换，A2A message 的 metadata 和 Direct params 共享同一组字段
        Map<String, Object> metadata = TaskDispatchRequestParams.toCommonParams(request);

        return A2aMessage.builder()
                .role("user")
                .parts(parts)
                .contextId(request.getContextId())
                .contextAlias(request.getContextAlias())
                .metadata(metadata)
                .build();
    }

    private TaskDispatchRequest toTaskDispatchRequest(AgentTaskSubmitRequest request) {
        A2aMessage message = request.getMessage();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (message != null && message.getMetadata() != null) {
            metadata.putAll(message.getMetadata());
        }
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        String contextId = firstNonBlank(request.getContextId(), message != null ? message.getContextId() : null);
        String contextAlias = firstNonBlank(request.getContextAlias(), message != null ? message.getContextAlias() : null);
        return TaskDispatchRequest.builder()
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .sessionId(request.getSessionId())
                .workerId(request.getWorkerId())
                .prompt(firstNonBlank(request.getPrompt(), extractTextPrompt(message)))
                .cwd(request.getCwd())
                .directoryId(request.getDirectoryId())
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .maxTurns(request.getMaxTurns())
                .permissionMode(request.getPermissionMode())
                .images(request.getImages())
                .attachments(request.getAttachments())
                .agentTeamsConfigId(request.getAgentTeamsConfigId())
                .agentTeamsJson(request.getAgentTeamsJson())
                .contextId(contextId)
                .context(request.getContext())
                .metadata(metadata.isEmpty() ? null : metadata)
                .contextAlias(contextAlias)
                .build();
    }

    private String extractTextPrompt(A2aMessage message) {
        if (message == null || message.getParts() == null) {
            return null;
        }
        return message.getParts().stream()
                .filter(Objects::nonNull)
                .filter(part -> "text".equals(part.getType()))
                .map(A2aPart::getText)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
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
                    .codexThreadId(strVal(meta, "codexThreadId"))
                    .geminiSessionId(strVal(meta, "geminiSessionId"))
                    .model(strVal(meta, "model"))
                    .modelConfigId(strVal(meta, "modelConfigId"));
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
                    .cwd(request.getCwd());
            if (dto.getModel() == null) {
                builder.model(request.getModel());
            }
            if (dto.getModelConfigId() == null) {
                builder.modelConfigId(request.getModelConfigId());
            }
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

        // 从 Session 补充 parentSessionId（转发创建新会话时需要）
        DispatchTaskDTO preResult = builder.build();
        if (preResult.getSessionId() != null && preResult.getParentSessionId() == null) {
            sessionRepository.findById(preResult.getSessionId()).ifPresent(session -> {
                if (session.getParentSessionId() != null) {
                    builder.parentSessionId(session.getParentSessionId());
                }
            });
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

    public A2aTask toA2aTask(DispatchTaskDTO dto) {
        if (dto == null) {
            throw new IllegalStateException("Task submit pipeline returned no task");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "sessionId", dto.getSessionId());
        putIfNotBlank(metadata, "workerId", dto.getWorkerId());
        putIfNotBlank(metadata, "workerTaskId", dto.getWorkerTaskId());
        putIfNotBlank(metadata, "directoryId", dto.getDirectoryId());
        putIfNotBlank(metadata, "model", dto.getModel());
        putIfNotBlank(metadata, "modelConfigId", dto.getModelConfigId());
        putIfNotBlank(metadata, "claudeSessionId", dto.getClaudeSessionId());
        putIfNotBlank(metadata, "codexThreadId", dto.getCodexThreadId());
        putIfNotBlank(metadata, "geminiSessionId", dto.getGeminiSessionId());
        if (dto.getLastAckedSeq() != null) {
            metadata.put("lastAckedSeq", dto.getLastAckedSeq());
        }
        if (dto.getDurationMs() != null) {
            metadata.put("durationMs", dto.getDurationMs());
        }
        if (dto.getCostUsd() != null) {
            metadata.put("costUsd", dto.getCostUsd());
        }
        return A2aTask.builder()
                .id(dto.getTaskId())
                .contextId(dto.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(toA2aState(dto.getStatus()))
                        .description(dto.getErrorMessage())
                        .build())
                .artifacts(buildArtifacts(dto.getResultText()))
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    private A2aTaskState toA2aState(String status) {
        if (status == null || status.isBlank()) {
            return A2aTaskState.SUBMITTED;
        }
        return switch (status) {
            case "PENDING" -> A2aTaskState.SUBMITTED;
            case "RUNNING" -> A2aTaskState.WORKING;
            case "AWAITING_INPUT", "AWAITING_PERMISSION" -> A2aTaskState.INPUT_REQUIRED;
            case "COMPLETED" -> A2aTaskState.COMPLETED;
            case "FAILED" -> A2aTaskState.FAILED;
            case "ABORTED", "CANCELLED", "CANCELED" -> A2aTaskState.CANCELED;
            default -> A2aTaskState.WORKING;
        };
    }

    private List<A2aArtifact> buildArtifacts(String resultText) {
        if (resultText == null || resultText.isBlank()) {
            return null;
        }
        return List.of(A2aArtifact.builder()
                .parts(List.of(A2aPart.text(resultText)))
                .build());
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
        return projectionService().buildTaskPageResponse(taskItems, totalSessions, page, size);
    }

    private UnifiedSessionTaskProjectionService.TaskPageEnvelope toTaskPageEnvelope(Object pageResult) {
        return projectionService().toTaskPageEnvelope(pageResult);
    }

    private UnifiedSessionTaskProjectionService.SearchEnvelope toSearchEnvelope(Object searchResult) {
        return projectionService().toSearchEnvelope(searchResult);
    }

    private String readStringProperty(Object target, String property) {
        return projectionService().readStringProperty(target, property);
    }

    private LocalDateTime readDateTimeProperty(Object target, String property) {
        return projectionService().readDateTimeProperty(target, property);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        return projectionService().compareNullableTime(left, right);
    }

    private Object listTasksPagedFromSessionStore(String userId, String directoryId, int page, int size, String state,
                                                   boolean compact) {
        return projectionService().listTasksPagedFromSessionStore(
                sessionTaskRepository, userId, directoryId, page, size, state, compact);
    }

    private Object searchSessionsFromSessionStore(String userId, String keyword, String workerId,
                                                   String directoryId, int page, int size) {
        return projectionService().searchSessionsFromSessionStore(
                sessionTaskRepository, userId, keyword, workerId, directoryId, page, size);
    }

    private Object toCompactTaskItem(Object task) {
        return projectionService().toCompactTaskItem(task);
    }

    private List<DispatchTaskDTO> toDispatchTaskDTOs(List<SessionTaskEntity> entities) {
        return projectionService().toDispatchTaskDTOs(entities);
    }

    private DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity) {
        return projectionService().toDispatchTaskDTO(entity);
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

    /**
     * Post-dispatch: 将 request 中的 model / modelConfigId 持久化到 SessionTaskEntity。
     * 这是 model 和 modelConfigId 写入 session_tasks 的统一入口，在 Facade 层公共处理，各 Provider 无需关心。
     */
    private void persistTaskRequestFields(String taskId, TaskDispatchRequest request) {
        if (sessionTaskRepository == null || taskId == null) return;
        String model = request.getModel();
        String modelConfigId = request.getModelConfigId();
        boolean hasModel = model != null && !model.isBlank();
        boolean hasModelConfigId = modelConfigId != null && !modelConfigId.isBlank();
        boolean hasContextId = request.getContextId() != null && !request.getContextId().isBlank();
        boolean hasDiagnostics = hasDiagnosticMetadata(request.getMetadata());
        if (!hasModel && !hasModelConfigId && !hasContextId && !hasDiagnostics) return;

        sessionTaskRepository.findByTaskId(taskId).ifPresent(st -> {
            if (hasModel) st.setModel(model);
            if (hasModelConfigId) st.setModelConfigId(modelConfigId);
            if (hasContextId || hasDiagnostics) {
                Map<String, Object> state = new LinkedHashMap<>();
                if (hasContextId) {
                    state.put("contextId", request.getContextId().trim());
                }
                copyDiagnosticMetadata(state, request.getMetadata());
                st.setTaskStateJson(ProviderStateCodec.mergeTaskValues(
                        st.getTaskStateJson(), st.getProviderType(), state));
            }
            sessionTaskRepository.save(st);
        });
    }

    private boolean hasDiagnosticMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        for (String key : diagnosticMetadataKeys()) {
            if (metadata.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private void copyDiagnosticMetadata(Map<String, Object> state, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (String key : diagnosticMetadataKeys()) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                state.put(key, text.trim());
            } else if (value instanceof Number || value instanceof Boolean) {
                state.put(key, value);
            }
        }
    }

    private List<String> diagnosticMetadataKeys() {
        return List.of(
                "modelConfigId",
                "modelConfigSource",
                "workerBackend",
                "agentSource",
                "workerSource",
                "backendSource",
                "taskSource",
                "contextId",
                "originalTaskId",
                "original_task_id",
                "sourceTaskId",
                "source_task_id",
                "recoveryCorrelationKey",
                "recovery_correlation_key",
                "correlationKey",
                "correlation_key",
                "attemptNumber",
                "attempt_number",
                "attempt",
                "idempotencyKey",
                "idempotency_key");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private DispatchTaskDTO createTaskDirect(String providerType, TaskDispatchRequest request, AgentResolveContext context) {
        DispatchTaskDTO dto = operationRouter().createTaskDirect(providerType, request, context);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, providerType);
        return dto;
    }

    private boolean bindContinuationFromContext(TaskDispatchRequest request, AgentResolveContext context) {
        if (agentConversationContextRepository == null) {
            return false;
        }
        String contextId = trimToNull(request.getContextId());
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        if (contextId == null || userId == null) {
            return false;
        }

        AgentConversationContextEntity boundContext = agentConversationContextRepository
                .findByContextIdAndUserId(contextId, userId)
                .orElse(null);
        if (boundContext == null) {
            return false;
        }

        validateContextAgentCompatibility(request, boundContext);

        String navigatorSessionId = trimToNull(boundContext.getNavigatorSessionId());
        if (navigatorSessionId == null) {
            return false;
        }

        String requestedSessionId = trimToNull(request.getSessionId());
        if (requestedSessionId != null && !requestedSessionId.equals(navigatorSessionId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                            + " is bound to session " + navigatorSessionId
                            + ", but request sessionId is " + requestedSessionId);
        }

        SessionEntity session = sessionRepository.findById(navigatorSessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                                + " is bound to missing session " + navigatorSessionId));
        validateContextSessionCompatibility(request, session);
        request.setSessionId(navigatorSessionId);
        fillFromBoundSession(request, session);
        String targetAgentId = trimToNull(boundContext.getTargetAgentId());
        if (trimToNull(request.getAgentId()) == null && targetAgentId != null) {
            request.setAgentId(targetAgentId);
        }
        return true;
    }

    private void validateContextBindingBeforeDispatch(TaskDispatchRequest request, AgentResolveContext context) {
        if (agentConversationContextRepository == null || request == null) {
            return;
        }
        String contextId = trimToNull(request.getContextId());
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        if (contextId == null || userId == null) {
            return;
        }

        agentConversationContextRepository.findById(contextId).ifPresent(existing -> {
            String existingUserId = trimToNull(existing.getUserId());
            if (existingUserId != null && !existingUserId.equals(userId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: contextId " + contextId + " is already bound to another user");
            }
            String existingTargetAgentId = trimToNull(existing.getTargetAgentId());
            String requestedAgentId = trimToNull(request.getAgentId());
            if (existingTargetAgentId != null && requestedAgentId != null && !existingTargetAgentId.equals(requestedAgentId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: contextId " + contextId
                                + " is already bound to agent " + existingTargetAgentId
                                + ", but request agentId is " + requestedAgentId);
            }
            String existingSessionId = trimToNull(existing.getNavigatorSessionId());
            String requestedSessionId = trimToNull(request.getSessionId());
            if (existingSessionId != null && requestedSessionId != null && !existingSessionId.equals(requestedSessionId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                                + " is bound to session " + existingSessionId
                                + ", but request sessionId is " + requestedSessionId);
            }
        });
    }

    private void validateContextAgentCompatibility(TaskDispatchRequest request,
                                                   AgentConversationContextEntity boundContext) {
        String requestedAgentId = trimToNull(request.getAgentId());
        String targetAgentId = trimToNull(boundContext.getTargetAgentId());
        if (requestedAgentId != null && targetAgentId != null && !requestedAgentId.equals(targetAgentId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: contextId " + boundContext.getContextId()
                            + " is bound to agent " + targetAgentId
                            + ", but request agentId is " + requestedAgentId);
        }
    }

    private void validateContextSessionCompatibility(TaskDispatchRequest request, SessionEntity session) {
        String boundProvider = trimToNull(session.getProviderType());
        String requestedProvider = trimToNull(request.getProviderType());
        if (requestedProvider != null && boundProvider != null && !requestedProvider.equals(boundProvider)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: providerType " + requestedProvider
                            + " conflicts with context/session-bound provider " + boundProvider);
        }

        String boundWorkerId = trimToNull(session.getCurrentWorkerId());
        String requestedWorkerId = trimToNull(request.getWorkerId());
        if (requestedWorkerId != null && boundWorkerId != null && !requestedWorkerId.equals(boundWorkerId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: workerId " + requestedWorkerId
                            + " conflicts with context/session-bound worker " + boundWorkerId);
        }

        String boundDirectoryId = trimToNull(session.getCurrentDirectoryId());
        String requestedDirectoryId = trimToNull(request.getDirectoryId());
        if (requestedDirectoryId != null && boundDirectoryId != null && !requestedDirectoryId.equals(boundDirectoryId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: directoryId " + requestedDirectoryId
                            + " conflicts with context/session-bound directory " + boundDirectoryId);
        }
    }

    private void fillFromBoundSession(TaskDispatchRequest request, SessionEntity session) {
        if (trimToNull(request.getProviderType()) == null) {
            request.setProviderType(trimToNull(session.getProviderType()));
        }
        if (trimToNull(request.getWorkerId()) == null) {
            request.setWorkerId(trimToNull(session.getCurrentWorkerId()));
        }
        if (trimToNull(request.getDirectoryId()) == null) {
            request.setDirectoryId(trimToNull(session.getCurrentDirectoryId()));
        }
    }

    private void persistContextBinding(DispatchTaskDTO dto,
                                       TaskDispatchRequest request,
                                       AgentResolveContext context,
                                       String providerType) {
        if (agentConversationContextRepository == null || dto == null) {
            return;
        }
        String contextId = trimToNull(firstNonBlank(dto.getContextId(), request.getContextId()));
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        String sessionId = trimToNull(dto.getSessionId());
        if (contextId == null || userId == null || sessionId == null) {
            return;
        }

        String targetAgentId = firstNonBlank(dto.getAgentId(), request.getAgentId(), providerType, dto.getProviderType());
        String resolvedProviderType = firstNonBlank(dto.getProviderType(), providerType, request.getProviderType());
        String agentSessionRef = firstNonBlank(
                dto.getClaudeSessionId(),
                dto.getCodexThreadId(),
                dto.getGeminiSessionId(),
                dto.getWorkerTaskId());

        AgentConversationContextEntity entity = agentConversationContextRepository.findById(contextId)
                .orElseGet(AgentConversationContextEntity::new);
        if (entity.getContextId() == null) {
            entity.setContextId(contextId);
        }
        String existingUserId = trimToNull(entity.getUserId());
        if (existingUserId != null && !existingUserId.equals(userId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: contextId " + contextId + " is already bound to another user");
        }
        String existingTargetAgentId = trimToNull(entity.getTargetAgentId());
        if (existingTargetAgentId != null && targetAgentId != null && !existingTargetAgentId.equals(targetAgentId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: contextId " + contextId
                            + " is already bound to agent " + existingTargetAgentId
                            + ", but dispatched agent is " + targetAgentId);
        }

        entity.setUserId(userId);
        entity.setTargetAgentId(firstNonBlank(existingTargetAgentId, targetAgentId, resolvedProviderType));
        entity.setAgentType(firstNonBlank(resolvedProviderType, entity.getAgentType(), "unknown"));
        entity.setAgentSessionRef(firstNonBlank(agentSessionRef, entity.getAgentSessionRef()));
        entity.setNavigatorSessionId(sessionId);
        if (request.getContextAlias() != null && !request.getContextAlias().isBlank()) {
            entity.setContextAlias(request.getContextAlias().trim());
        }
        agentConversationContextRepository.save(entity);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String resolveLogicalAgentId(A2aAgent agent, String lookupId) {
        if (agent.getAgentCard() != null
                && agent.getAgentCard().getId() != null
                && !agent.getAgentCard().getId().isBlank()) {
            return agent.getAgentCard().getId();
        }
        return lookupId;
    }

}

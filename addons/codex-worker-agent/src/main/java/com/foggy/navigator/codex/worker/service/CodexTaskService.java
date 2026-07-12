package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
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
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.common.util.TaskResponseTimeoutSupport;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
public class CodexTaskService implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider {

    public static final String CODEX_PROVIDER_TYPE = "codex-worker";
    public static final String CODEX_APP_SERVER_PROVIDER_TYPE = "codex-app-server-worker";
    public static final String CODEX_BIZ_PROVIDER_TYPE = "codex-biz-worker";
    private static final String AGENT_ID = CODEX_PROVIDER_TYPE;
    private static final String USER_INPUT_STATE_KEY = "codexPendingInteraction";
    private static final String USER_INPUT_METHOD = "item/tool/requestUserInput";
    private static final int MAX_USER_INPUT_QUESTIONS = 3;
    private static final Set<String> CODEX_CATALOG_EFFORTS = Set.of(
            "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Map<String, String> CODEX_LEGACY_MODEL_VALUES = Map.ofEntries(
            Map.entry("codex-latest", "codex-latest:medium"),
            Map.entry("codex-fast", "codex-latest:low"),
            Map.entry("codex-deep", "codex-latest:high"),
            Map.entry("codex-xhigh", "codex-latest:xhigh"),
            Map.entry("codex-max", "codex-latest:max"),
            Map.entry("codex-ultra", "codex-latest:ultra"),
            Map.entry("codex-terra", "codex-terra:medium"),
            Map.entry("codex-luna", "codex-luna:medium"));
    private static final Map<String, String> CODEX_REAL_MODEL_FAMILIES = Map.of(
            "gpt-5.6-sol", "codex-latest",
            "gpt-5.6-terra", "codex-terra",
            "gpt-5.6-luna", "codex-luna");
    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESUME_TASK,
            TaskQueryCapability.RESPOND_TO_TASK,
            TaskQueryCapability.RECONNECT_TASK,
            TaskQueryCapability.CANCEL_TASK,
            TaskQueryCapability.DELETE_TASK,
            TaskQueryCapability.RESYNC_TASK,
            TaskQueryCapability.REWIND_TASK,
            TaskQueryCapability.LIST_TASKS_PAGED,
            TaskQueryCapability.SEARCH_SESSIONS,
            TaskQueryCapability.LIST_TASKS_BY_DIRECTORY_PAGED);

    private final CodexTaskRepository taskRepository;
    private final WorkerManagementFacade workerManagementFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskRuntimeStateService taskRuntimeStateService;

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
    private NativeSubtaskStateRepository nativeSubtaskStateRepository;

    @Autowired(required = false)
    @Nullable
    private CodexCodingAgentRepository codingAgentRepository;

    @Autowired
    @Lazy
    private CodexStreamRelay streamRelay;

    @Autowired(required = false)
    @Nullable
    private CodexRuntimeRegistryService runtimeRegistryService;

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
        return resumeTaskForProvider(CODEX_PROVIDER_TYPE, userId, tenantId, params);
    }

    @Transactional
    public DispatchTaskDTO resumeTaskForProvider(String expectedProviderType,
                                                  String userId,
                                                  String tenantId,
                                                  java.util.Map<String, Object> params) {
        String effectiveProviderType = requireProviderParams(expectedProviderType, params);
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        form.setProviderType(effectiveProviderType);
        if (isCodexBizProvider(form.getProviderType())) {
            applyCodexBizParams(form, params);
        }
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }

        // codexThreadId 从 SessionEntity.providerStateJson 恢复，不再从 request 透传
        String sessionId = (String) params.get("sessionId");
        if (sessionId != null && !sessionId.isBlank() && sessionEntityRepository != null) {
            String codexThreadId = ProviderStateCodec.readStringOrNull(
                    sessionEntityRepository.findById(sessionId)
                            .map(SessionEntity::getProviderStateJson).orElse(null),
                    ProviderStateCodec.FIELD_CODEX_THREAD_ID);
            form.setCodexThreadId(codexThreadId);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 sessionId");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 workerId");
        }

        workerManagementFacade.validateWorkerAccess(userId, tenantId, form.getWorkerId());
        validateExistingSession(userId, sessionId);
        lockExistingSessionForResume(userId, sessionId);
        if (sessionEntityRepository != null) {
            String lockedCodexThreadId = ProviderStateCodec.readStringOrNull(
                    sessionEntityRepository.findById(sessionId)
                            .map(SessionEntity::getProviderStateJson).orElse(null),
                    ProviderStateCodec.FIELD_CODEX_THREAD_ID);
            form.setCodexThreadId(lockedCodexThreadId);
        }

        if (form.getCodexThreadId() == null || form.getCodexThreadId().isBlank()) {
            // Platform-only rewind clears the native Codex thread. Continue by starting
            // a new Codex thread while reusing the Navigator session.
            CodexTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
            return getTaskByIdForProvider(task.getTaskId(), form.getProviderType()).orElseThrow();
        }

        if (!taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderType(
                form.getCodexThreadId(), form.getWorkerId(), userId, effectiveProviderType)) {
            throw new IllegalArgumentException("Codex 会话不存在或不属于该 Worker: " + form.getCodexThreadId());
        }
        if (taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeAndStatusIn(
                form.getCodexThreadId(), form.getWorkerId(), userId, effectiveProviderType,
                List.of("RUNNING", "AWAITING_INPUT"))) {
            throw new IllegalStateException("该会话正在运行任务，请等待完成或终止后再继续");
        }

        CodexTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
        return getTaskByIdForProvider(task.getTaskId(), form.getProviderType()).orElseThrow();
    }

    private CodexTaskDTO createAndStartTask(String userId, String tenantId,
                                            CreateCodexTaskForm form, String existingSessionId) {
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getPrompt() == null || form.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String effectiveProviderType = normalizeProviderType(form.getProviderType());
        form.setProviderType(effectiveProviderType);
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            validateExistingSession(userId, existingSessionId);
            validateExistingSessionProvider(existingSessionId, effectiveProviderType);
        }
        normalizeAndValidateCodexBizHomeKey(form, effectiveProviderType);

        // 验证 Worker 存在且当前 user/tenant 可访问（通过 WorkerManagementFacade SPI）
        workerManagementFacade.validateWorkerAccess(userId, tenantId, form.getWorkerId());

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

        String effectiveModelConfigId = resolveEffectiveModelConfigId(form.getModelConfigId(), effectiveAgentId);
        LlmModelConfigDTO effectiveModelConfig = validateAndResolveModelConfig(
                effectiveModelConfigId, form.getWorkerId());
        validateModelConfigProvider(effectiveModelConfigId, effectiveModelConfig, effectiveProviderType);
        ModelResolution effectiveModelResolution = resolveEffectiveModel(
                form.getModel(), effectiveAgentId, effectiveModelConfig);
        validateProviderModel(effectiveProviderType, effectiveModelResolution.model());
        validateEffectiveModelGrant(
                effectiveModelResolution.model(), effectiveModelConfigId, effectiveModelConfig);
        String modelConfigSource = resolveModelConfigSource(form.getModelConfigId(), effectiveAgentId);

        String taskId = IdGenerator.shortId();

        String sessionId = resolveSessionId(userId, tenantId, form.getPrompt(),
                existingSessionId, effectiveAgentId, effectiveProviderType);

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(form.getDirectoryId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setResolvedAgentId(effectiveAgentId);
        entity.setContextId(form.getContextId());
        entity.setProviderType(effectiveProviderType);
        if (isCodexBizProvider(effectiveProviderType)) {
            entity.setCodexHomeKey(form.getCodexHomeKey());
            entity.setPrivateAccountId(firstNonBlank(form.getPrivateAccountId(), form.getCodexHomeKey()));
        }
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setModel(effectiveModelResolution.model());
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(form.getCodexThreadId());
        applyRuntimeBinding(entity, resolveRuntimeBinding(
                form.getWorkerId(), entity.getModel(), effectiveProviderType, taskId, existingSessionId,
                runtimeRequirements(form, effectiveProviderType)));

        persistTask(entity);
        log.info("Created Codex task: taskId={}, providerType={}, workerId={}, sessionId={}",
                taskId, effectiveProviderType, form.getWorkerId(), sessionId);

        // 解析 auth + envVars（apiKey + baseUrl + 环境变量，无配置时 Worker 使用本地凭证）
        CodexAuthResult auth = resolveCodexAuth(effectiveModelConfigId);
        log.info(
                "Resolved Codex task auth: taskId={}, agentId={}, modelConfigId={}, modelConfigSource={}, model={}, modelSource={}, hasApiKey={}, hasBaseUrl={}, envVarKeys={}",
                taskId,
                effectiveAgentId,
                effectiveModelConfigId,
                modelConfigSource,
                entity.getModel(),
                effectiveModelResolution.source(),
                auth.apiKey != null && !auth.apiKey.isBlank(),
                auth.baseUrl != null && !auth.baseUrl.isBlank(),
                auth.envVars != null ? auth.envVars.keySet() : List.of()
        );

        // 发布统一事件触发 CodexStreamRelay（通过 providerType 条件过滤）
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        putIfNotBlank(providerConfig, "codexThreadId", form.getCodexThreadId());
        putIfNotBlank(providerConfig, "images", form.getImages());
        if (form.getAttachments() != null && !form.getAttachments().isEmpty()) {
            providerConfig.put("attachments", form.getAttachments());
        }
        putIfNotBlank(providerConfig, "baseUrl", auth.baseUrl);
        if (auth.envVars != null && !auth.envVars.isEmpty()) {
            providerConfig.put("extraEnvVars", auth.envVars);
        }
        if (isCodexBizProvider(effectiveProviderType)) {
            putIfNotBlank(providerConfig, "codexHomeKey", form.getCodexHomeKey());
            putIfNotBlank(providerConfig, "developerInstructions", form.getDeveloperInstructions());
            if (form.getBusinessRuntimeContext() != null && !form.getBusinessRuntimeContext().isEmpty()) {
                providerConfig.put("businessRuntimeContext", form.getBusinessRuntimeContext());
            }
            putIfNotBlank(providerConfig, "sandboxMode", form.getSandboxMode());
            putIfNotBlank(providerConfig, "approvalPolicy", form.getApprovalPolicy());
            putIfNotBlank(providerConfig, "webSearchMode", form.getWebSearchMode());
            if (form.getOutputSchema() != null && !form.getOutputSchema().isEmpty()) {
                providerConfig.put("outputSchema", form.getOutputSchema());
            }
            if (form.getCodexConfig() != null && !form.getCodexConfig().isEmpty()) {
                providerConfig.put("codexConfig", form.getCodexConfig());
            }
            if (form.getNetworkAccessEnabled() != null) {
                providerConfig.put("networkAccessEnabled", form.getNetworkAccessEnabled());
            }
            if (form.getAdditionalDirectories() != null && !form.getAdditionalDirectories().isEmpty()) {
                providerConfig.put("additionalDirectories", form.getAdditionalDirectories());
            }
        }

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .prompt(form.getPrompt()).cwd(cwd)
                .model(entity.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(auth.apiKey).providerType(effectiveProviderType)
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
        return createTrackedSyncTask(userId, workerId, sessionId, prompt, cwd,
                directoryId, codexThreadId, null);
    }

    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                         String prompt, String cwd, String directoryId,
                                         String codexThreadId, String model) {
        validateProviderModel(CODEX_PROVIDER_TYPE, model);
        if (sessionId != null && !sessionId.isBlank()) {
            validateExistingSession(userId, sessionId);
            validateExistingSessionProvider(sessionId, CODEX_PROVIDER_TYPE);
        }
        String taskId = IdGenerator.shortId();
        // Codex CLI (Rust) 不接受 Windows 反斜杠路径
        String normalizedCwd = cwd != null ? cwd.replace('\\', '/') : null;

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setProviderType(CODEX_PROVIDER_TYPE);
        entity.setPrompt(prompt);
        entity.setCwd(normalizedCwd);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(codexThreadId);
        entity.setModel(model);
        CodexRuntimeBinding binding;
        if (sessionId != null && !sessionId.isBlank()) {
            binding = resolveRuntimeBinding(
                    workerId, model, CODEX_PROVIDER_TYPE, taskId, sessionId);
        } else if (codexThreadId != null && !codexThreadId.isBlank()) {
            binding = resolveThreadRuntimeBinding(
                    codexThreadId, workerId, userId, CODEX_PROVIDER_TYPE);
        } else {
            binding = resolveRuntimeBinding(workerId, model, CODEX_PROVIDER_TYPE, taskId, null);
        }
        applyRuntimeBinding(entity, binding);

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
        recordWorkerProgress(taskId, workerTaskId, codexThreadId, model, ackSeq, false, false);
    }

    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq, boolean userVisibleOutput) {
        recordWorkerProgress(taskId, workerTaskId, codexThreadId, model, ackSeq,
                userVisibleOutput, userVisibleOutput);
    }

    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq, boolean userVisibleOutput,
                                      boolean executionCommitted) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("recordWorkerProgress: task not found: {}", taskId);
            return;
        }

        if (workerTaskId != null && !workerTaskId.isBlank()) {
            if (entity.getWorkerTaskId() != null && !entity.getWorkerTaskId().isBlank()
                    && !entity.getWorkerTaskId().equals(workerTaskId)) {
                throw new IllegalStateException(
                        "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: worker task id changed for " + taskId);
            }
            entity.setWorkerTaskId(workerTaskId);
        }
        if (codexThreadId != null && !codexThreadId.isBlank()) {
            entity.setCodexThreadId(codexThreadId);
        }
        applyWorkerReportedModel(entity, model);
        if (ackSeq != null) {
            Integer current = entity.getLastAckedSeq();
            entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
        }
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                && executionCommitted
                && !isTerminalStatus(entity.getStatus())
                && !"TERMINAL".equals(entity.getRuntimeAcceptanceState())) {
            entity.setRuntimeAcceptanceState("COMMITTED");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        if (userVisibleOutput) {
            entity.setLastOutputAt(now);
        }
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

    public CodexTaskDTO getTaskForProvider(String userId, String taskId, String providerType) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
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
        return toDTOs(taskRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    public List<CodexTaskDTO> listTasksForProvider(String userId, String providerType) {
        return toDTOs(filterTasksByProvider(
                taskRepository.findByUserIdOrderByCreatedAtDesc(userId), providerType));
    }

    /**
     * 列出 Worker 下的任务
     */
    public List<CodexTaskDTO> listTasksByWorker(String userId, String workerId) {
        return toDTOs(taskRepository.findByWorkerIdAndUserId(workerId, userId));
    }

    public List<CodexTaskDTO> listTasksByWorkerForProvider(
            String userId, String workerId, String providerType) {
        return toDTOs(filterTasksByProvider(
                taskRepository.findByWorkerIdAndUserId(workerId, userId), providerType));
    }

    @Override
    public void cancelTaskDirect(String taskId, String userId) {
        cancelTaskDirectForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    public void cancelTaskDirectForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if ("RUNNING".equals(entity.getStatus()) || "AWAITING_PERMISSION".equals(entity.getStatus())
                || "AWAITING_INPUT".equals(entity.getStatus())) {
            abortTask(taskId);
        }
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public void cancelTask(String taskId, String userId) {
        cancelTaskDirect(taskId, userId);
    }

    @Override
    public void reconnectTask(String taskId, String userId) {
        reconnectTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    public void reconnectTaskForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"RUNNING".equals(entity.getStatus()) && !"AWAITING_INPUT".equals(entity.getStatus())) {
            return;
        }
        streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
    }

    /**
     * 检查指定 Codex 会话是否有正在运行的任务（并发保护）
     */
    public boolean hasRunningTask(String codexThreadId, String workerId, String userId) {
        return taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndStatusIn(
                codexThreadId, workerId, userId, List.of("RUNNING", "AWAITING_INPUT"));
    }

    public boolean hasRunningTaskForProvider(String codexThreadId, String workerId,
                                             String userId, String providerType) {
        return taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeAndStatusIn(
                codexThreadId, workerId, userId, normalizeProviderType(providerType),
                List.of("RUNNING", "AWAITING_INPUT"));
    }

    /** Registers the sanitized app-server requestUserInput projection before it is acknowledged. */
    @Transactional
    public UserInputRegistration registerPendingUserInput(String taskId, Map<String, Object> projection) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_UNSUPPORTED");
        }
        if (isTerminalStatus(entity.getStatus())) {
            return UserInputRegistration.ignored();
        }

        Map<String, Object> pending = sanitizePendingUserInput(entity, projection);
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        SessionTaskEntity sessionTask = requireSessionTask(entity);
        Map<String, Object> existing = pendingUserInput(sessionTask);
        if (!existing.isEmpty()) {
            String existingState = stringValue(existing.get("state"));
            if (sameWireRequestId(pending.get("request_id"), existing.get("request_id"))) {
                if ("RESOLVED".equals(existingState)) {
                    return UserInputRegistration.ignored();
                }
                if (!"PENDING".equals(existingState)) {
                    throw interactionError("CODEX_USER_INPUT_STATE_INVALID");
                }
                if (!samePendingUserInput(existing, pending)) {
                    throw interactionError("CODEX_USER_INPUT_REPLAY_MISMATCH");
                }
            } else if ("PENDING".equals(existingState)) {
                throw interactionError("CODEX_USER_INPUT_OVERLAP");
            }
        }

        pending.put("state", "PENDING");
        savePendingUserInput(sessionTask, entity, pending);
        String previousStatus = entity.getStatus();
        if (entity.getCodexThreadId() == null || entity.getCodexThreadId().isBlank()) {
            entity.setCodexThreadId(stringValue(pending.get("thread_id")));
        }
        entity.setStatus("AWAITING_INPUT");
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        if (!"AWAITING_INPUT".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
        return new UserInputRegistration(true, requestId, confirmationPayload(taskId, pending));
    }

    /**
     * Sends one response to the exact bound runtime. The task row lock serializes concurrent replies;
     * answers are never written to task state.
     */
    @Override
    @Transactional
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        respondToTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId, response);
    }

    @Transactional
    public void respondToTaskForProvider(String providerType, String taskId, String userId,
                                         Map<String, Object> response) {
        requireUserInputPersistence();
        if (response == null) {
            throw new IllegalArgumentException("response is required");
        }
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"AWAITING_INPUT".equals(entity.getStatus())) {
            throw interactionError("CODEX_USER_INPUT_NOT_PENDING");
        }
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_UNSUPPORTED");
        }

        SessionTaskEntity sessionTask = requireSessionTask(entity);
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (!"PENDING".equals(stringValue(pending.get("state")))) {
            throw interactionError("CODEX_USER_INPUT_NOT_PENDING");
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        Object suppliedRequestId = firstNonNull(
                response.get("requestId"), response.get("request_id"), response.get("permissionId"));
        if (!(suppliedRequestId instanceof String suppliedToken) || !requestId.equals(suppliedToken)) {
            throw interactionError("CODEX_USER_INPUT_REQUEST_MISMATCH");
        }

        NormalizedUserInputAnswers normalized = normalizeUserInputAnswers(pending, response.get("answers"));
        CodexRuntimeBinding binding = bindingFromTask(entity, entity.getWorkerId());
        if (binding == null || binding.getRuntimeType() != CodexRuntimeType.APP_SERVER
                || binding.getInstanceId() == null || binding.getInstanceId().isBlank()) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST");
        }
        String workerTaskId = stringValue(entity.getWorkerTaskId());
        if (workerTaskId == null) {
            throw interactionError("CODEX_USER_INPUT_REMOTE_TASK_MISSING");
        }

        CodexWorkerClient client = clientFactory.getOrCreate(
                "runtime:" + binding.getRuntimeId() + ":" + binding.getRuntimeRevision(),
                binding.getEndpointUrl(), binding.getAuthToken(), binding.getInstanceId());
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("request_id", pending.get("request_id"));
        requestBody.put("answers", normalized.workerAnswers());

        Map<String, Object> remoteResult = null;
        boolean alreadyResponded = false;
        try {
            remoteResult = client.respondToTask(workerTaskId, requestBody).block(Duration.ofSeconds(15));
        } catch (RuntimeException error) {
            CodexWorkerClient.UserInputResponseException responseError = findUserInputResponseError(error);
            if (responseError != null && "USER_INPUT_ALREADY_RESPONDED".equals(responseError.getCode())) {
                alreadyResponded = true;
            } else if (responseError != null) {
                throw interactionError(responseError.getCode(), error);
            } else if (findRuntimeProofError(error) != null) {
                throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST", error);
            } else {
                throw interactionError("CODEX_USER_INPUT_RESPONSE_UNKNOWN", error);
            }
        }
        if (!alreadyResponded) {
            validateUserInputResponse(remoteResult, workerTaskId, pending.get("request_id"));
        }

        markPendingResolved(sessionTask, entity, pending, "answered");
        String previousStatus = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        publishStatusChange(entity, previousStatus);
        streamRelay.publishUserInputResponse(
                entity.getSessionId(), resolveProviderType(entity), entity.getTaskId(), requestId,
                "allow", normalized.containsSecret() ? null : normalized.historyAnswers());
    }

    /** Applies Worker-side clearing/auto-resolution without reopening a completed request. */
    @Transactional
    public UserInputResolution resolvePendingUserInput(String taskId, Map<String, Object> resolution) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            return UserInputResolution.ignored();
        }
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (sessionTask == null) {
            return UserInputResolution.ignored();
        }
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (pending.isEmpty() || "RESOLVED".equals(stringValue(pending.get("state")))) {
            return UserInputResolution.ignored();
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        Object resolvedRequestId = resolution != null
                ? firstNonNull(resolution.get("request_id"), resolution.get("requestId")) : null;
        if (resolvedRequestId != null
                && !sameWireRequestId(pending.get("request_id"), resolvedRequestId)) {
            log.warn("Ignoring stale Codex user input resolution: taskId={}, requestId={}", taskId, requestId);
            return UserInputResolution.ignored();
        }
        String reason = resolution != null ? stringValue(resolution.get("reason")) : null;
        if (reason == null || !Set.of("answered", "cleared", "auto_resolved").contains(reason)) {
            reason = "cleared";
        }
        markPendingResolved(sessionTask, entity, pending, reason);
        String previousStatus = entity.getStatus();
        if ("AWAITING_INPUT".equals(previousStatus)) {
            entity.setStatus("RUNNING");
            entity.setErrorMessage(null);
            entity.setLastAliveAt(LocalDateTime.now());
            persistTask(entity);
            publishStatusChange(entity, previousStatus);
        }
        return new UserInputResolution(true, requestId, reason);
    }

    /** Closes an orphaned card before a terminal Worker event is projected. */
    @Transactional
    public UserInputResolution resolvePendingUserInputForTerminal(String taskId) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null || sessionTask == null) {
            return UserInputResolution.ignored();
        }
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (!"PENDING".equals(stringValue(pending.get("state")))) {
            return UserInputResolution.ignored();
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        markPendingResolved(sessionTask, entity, pending, "cleared");
        return new UserInputResolution(true, requestId, "cleared");
    }

    public record UserInputRegistration(boolean shouldPublish, String requestId,
                                        Map<String, Object> confirmationPayload) {
        private static UserInputRegistration ignored() {
            return new UserInputRegistration(false, null, Map.of());
        }
    }

    public record UserInputResolution(boolean shouldPublish, String requestId, String reason) {
        private static UserInputResolution ignored() {
            return new UserInputResolution(false, null, null);
        }

        public String decision() {
            return "answered".equals(reason) ? "allow" : "deny";
        }
    }

    private record NormalizedUserInputAnswers(Map<String, List<String>> workerAnswers,
                                              Map<String, String> historyAnswers,
                                              boolean containsSecret) {
    }

    /**
     * 中止任务（完整流程：terminal guard + doAbortWorkerTask）。
     * Provider Controller 和 SPI 入口调用此方法。
     */
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
    public void doAbortWorkerTask(String taskId, String remoteTaskId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        streamRelay.abortAndReconcileTask(entity);
    }

    /**
     * 标记任务完成
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String codexThreadId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("completeTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"COMPLETED".equals(previousStatus)) {
            log.warn("Ignoring late Codex completion for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        entity.setStatus("COMPLETED");
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity.setRuntimeAcceptanceState("TERMINAL");
        }
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        if (resultText != null) entity.setResultText(resultText);
        if (costUsd != null) entity.setCostUsd(costUsd);
        if (inputTokens != null) entity.setInputTokens(inputTokens);
        if (outputTokens != null) entity.setOutputTokens(outputTokens);
        if (durationMs != null) entity.setDurationMs(durationMs);
        if (numTurns != null) entity.setNumTurns(numTurns);
        applyWorkerReportedModel(entity, model);
        entity.setErrorMessage(null);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);

        persistTask(entity);
        log.info("Completed Codex task: taskId={}, cost={}", taskId, costUsd);
        if (!"COMPLETED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    private void applyWorkerReportedModel(CodexTaskEntity entity, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                && entity.getModel() != null
                && !entity.getModel().isBlank()) {
            return;
        }
        entity.setModel(model);
    }

    /**
     * 标记任务失败
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String codexThreadId, String errorMessage) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("failTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"FAILED".equals(previousStatus)) {
            log.warn("Ignoring late Codex failure for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        String stableError = stableTaskError(entity, errorMessage);
        entity.setStatus("FAILED");
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity.setRuntimeAcceptanceState("TERMINAL");
        }
        entity.setErrorMessage(stableError);
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);

        persistTask(entity);
        log.info("Failed Codex task: taskId={}, error={}", taskId, stableError);
        if (!"FAILED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    @Transactional
    public void reconcileAbortedTask(String taskId, String workerTaskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) return;
        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"ABORTED".equals(previousStatus)) {
            log.warn("Ignoring late Codex abort for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        entity.setStatus("ABORTED");
        entity.setRuntimeAcceptanceState("TERMINAL");
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        log.info("Reconciled remotely aborted Codex task: taskId={}", taskId);
        if (!"ABORTED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    /** Atomically closes PREPARED only when no acceptance attempt has started. */
    @Transactional
    public boolean failTaskIfAcceptanceNotStarted(String taskId, String failureCode) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null || isTerminalStatus(entity.getStatus())) {
            return true;
        }
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                || !"PREPARED".equals(entity.getRuntimeAcceptanceState())) {
            return false;
        }
        String previousStatus = entity.getStatus();
        entity.setStatus("FAILED");
        entity.setRuntimeAcceptanceState("TERMINAL");
        entity.setErrorMessage(failureCode);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        persistTask(entity);
        publishStatusChange(entity, previousStatus);
        return true;
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status);
    }

    private void validateTerminalWorkerTaskId(CodexTaskEntity entity, String workerTaskId) {
        if (workerTaskId != null && !workerTaskId.isBlank()
                && entity.getWorkerTaskId() != null && !entity.getWorkerTaskId().isBlank()
                && !entity.getWorkerTaskId().equals(workerTaskId)) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: worker task id changed for " + entity.getTaskId());
        }
    }

    private String stableTaskError(CodexTaskEntity entity, String errorMessage) {
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            return errorMessage;
        }
        if (errorMessage != null && errorMessage.startsWith("CODEX_")
                && errorMessage.length() <= 256
                && errorMessage.chars().noneMatch(Character::isISOControl)
                && !errorMessage.toLowerCase(Locale.ROOT).contains("http://")
                && !errorMessage.toLowerCase(Locale.ROOT).contains("https://")
                && !errorMessage.toLowerCase(Locale.ROOT).contains("bearer ")) {
            return errorMessage;
        }
        return "CODEX_RUNTIME_TASK_FAILED";
    }

    /**
     * 更新 Codex Thread ID
     */
    @Transactional
    public void updateCodexThreadId(String taskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity != null && codexThreadId != null) {
            entity.setCodexThreadId(codexThreadId);
            persistTask(entity);
        }
    }

    // ── Task provider narrow port implementations ──

    @Override
    public String getProviderType() {
        return AGENT_ID;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    @Transactional
    public DispatchTaskDTO createTaskDirect(java.util.Map<String, Object> params,
                                             String userId, String tenantId) {
        return createTaskDirectForProvider(CODEX_PROVIDER_TYPE, params, userId, tenantId);
    }

    @Transactional
    public DispatchTaskDTO createTaskDirectForProvider(String expectedProviderType,
                                                        java.util.Map<String, Object> params,
                                                        String userId,
                                                        String tenantId) {
        String effectiveProviderType = requireProviderParams(expectedProviderType, params);
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        form.setCodexThreadId((String) params.get("codexThreadId"));
        form.setProviderType(effectiveProviderType);
        if (isCodexBizProvider(form.getProviderType())) {
            applyCodexBizParams(form, params);
        }
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        CodexTaskDTO dto = createTask(userId, tenantId, form);
        return getTaskByIdForProvider(dto.getTaskId(), form.getProviderType()).orElseThrow();
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskById(String taskId) {
        return getTaskByIdForProvider(taskId, AGENT_ID);
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
        return getTaskByIdAndUserForProvider(taskId, userId, AGENT_ID);
    }

    @Override
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        return listTasksBySessionForProvider(sessionId, AGENT_ID);
    }

    public Optional<DispatchTaskDTO> getTaskByIdForProvider(String taskId, String providerType) {
        return taskRepository.findByTaskId(taskId)
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO);
    }

    public Optional<DispatchTaskDTO> getTaskByIdAndUserForProvider(String taskId, String userId, String providerType) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId)
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO);
    }

    public List<DispatchTaskDTO> listTasksBySessionForProvider(String sessionId, String providerType) {
        return taskRepository.findBySessionId(sessionId).stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return listActiveDispatchTasksForProvider(userId, AGENT_ID);
    }

    public List<DispatchTaskDTO> listActiveDispatchTasksForProvider(String userId, String providerType) {
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                        userId, List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT")).stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public TaskPageResult listTaskPage(String userId, int page, int size, String state) {
        return listTasksPagedForProvider(userId, page, size, state, AGENT_ID);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTaskPage(userId, page, size, state);
    }

    public TaskPageResult listTasksPagedForProvider(String userId, int page, int size, String state, String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        tasks = filterTasksByProvider(tasks, providerType);
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public TaskPageResult listDirectoryTaskPage(String userId, String directoryId, int page, int size, String state) {
        return listTasksByDirectoryPagedForProvider(userId, directoryId, page, size, state, AGENT_ID);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object listTasksByDirectoryPaged(String userId, String directoryId, int page, int size, String state) {
        return listDirectoryTaskPage(userId, directoryId, page, size, state);
    }

    public TaskPageResult listTasksByDirectoryPagedForProvider(String userId, String directoryId, int page, int size,
                                                               String state, String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        tasks = filterTasksByProvider(tasks, providerType);
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public TaskSearchResult searchSessionPage(String userId, String keyword, String workerId,
                                              String directoryId, int page, int size) {
        boolean hasFilter = (keyword != null && !keyword.isBlank())
                || (workerId != null && !workerId.isBlank())
                || (directoryId != null && !directoryId.isBlank());
        if (!hasFilter) {
            return TaskSearchResult.empty(page, size);
        }

        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return searchSessionsForProvider(userId, normalizedKeyword, workerId, directoryId, page, size, AGENT_ID);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object searchSessions(String userId, String keyword, String workerId,
                                 String directoryId, int page, int size) {
        return searchSessionPage(userId, keyword, workerId, directoryId, page, size);
    }

    public TaskSearchResult searchSessionsForProvider(String userId, String normalizedKeyword, String workerId,
                                                      String directoryId, int page, int size, String providerType) {
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(
                filterTasksByProvider(taskRepository.findByUserIdOrderByCreatedAtDesc(userId), providerType)
        ).values());

        List<Map<String, Object>> filtered = sessions.stream()
                .filter(tasks -> matchesSessionFilters(tasks, normalizedKeyword, workerId, directoryId))
                .map(this::toSearchResult)
                .sorted((a, b) -> compareNullableTime((LocalDateTime) b.get("updatedAt"), (LocalDateTime) a.get("updatedAt")))
                .toList();

        long total = filtered.size();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return TaskSearchResult.of(filtered.subList(from, to), total, page, size);
    }

    private DispatchTaskDTO toDispatchDTO(CodexTaskEntity entity) {
        String agentId = resolveLogicalAgentId(entity);
        String providerType = resolveProviderType(entity);
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .runtimeId(entity.getRuntimeId())
                .runtimeRevision(entity.getRuntimeRevision())
                .runtimeType(entity.getRuntimeType())
                .runtimeInstanceId(entity.getRuntimeInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .runtimeAcceptanceState(entity.getRuntimeAcceptanceState())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(agentId)
                .providerType(providerType)
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
                .lastOutputAt(entity.getLastOutputAt())
                .responseTimedOut(TaskResponseTimeoutSupport.isResponseTimedOut(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .silentForSeconds(TaskResponseTimeoutSupport.silentForSeconds(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .responseTimeoutThresholdSeconds(TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS)
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                // Codex-specific
                .codexThreadId(entity.getCodexThreadId())
                .contextId(resolveTaskContextId(entity))
                .build();
    }

    @Override
    @Transactional
    public void deleteTask(String userId, String taskId) {
        deleteTaskForProvider(CODEX_PROVIDER_TYPE, userId, taskId);
    }

    @Transactional
    public void deleteTaskForProvider(String providerType, String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);

        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity = taskRuntimeStateService.claimTerminalDeletion(taskId, userId);
            deleteTerminalAppServerTask(entity);
        } else if ("RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("Cannot delete a running task. Please abort it first.");
        }
        if (nativeSubtaskStateRepository != null) {
            nativeSubtaskStateRepository.deleteByTaskId(taskId);
        }
        taskRepository.delete(entity);
        log.info("Codex task deleted: taskId={}, userId={}", taskId, userId);
    }

    private void deleteTerminalAppServerTask(CodexTaskEntity entity) {
        if (!isTerminalStatus(entity.getStatus())) {
            throw new IllegalStateException(
                    "Cannot delete a non-terminal app-server task. Please abort it first.");
        }
        if (runtimeRegistryService == null) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_REGISTRY_UNAVAILABLE: cannot resolve task affinity for deletion");
        }
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                entity.getRuntimeId(), entity.getRuntimeRevision(), entity.getWorkerId(),
                entity.getRuntimeInstanceId());
        if (binding.getRuntimeType() != CodexRuntimeType.APP_SERVER) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_AFFINITY_MISMATCH: app-server task resolved to another runtime type");
        }

        String remoteTaskId = firstNonBlank(entity.getWorkerTaskId(), entity.getTaskId());
        CodexWorkerClient client = clientFactory.getOrCreate(
                "runtime:" + binding.getRuntimeId() + ":" + binding.getRuntimeRevision(),
                binding.getEndpointUrl(), binding.getAuthToken(), binding.getInstanceId());
        Boolean removed = client.deleteTask(remoteTaskId).block(Duration.ofSeconds(15));
        if (removed == null) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_DELETE_UNKNOWN: app-server returned no deletion result");
        }
        log.info("Codex app-server task cleanup confirmed: taskId={}, remoteTaskId={}, removed={}",
                entity.getTaskId(), remoteTaskId, removed);
    }

    @Override
    @Transactional
    public Object resyncTask(String taskId, String userId) {
        return resyncTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    @Transactional
    public Object resyncTaskForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be resynced, current: " + entity.getStatus());
        }
        if ("DELETE_REQUESTED".equals(entity.getRuntimeAcceptanceState())) {
            throw new IllegalStateException("Cannot resync a task while deletion is pending");
        }
        if (entity.getWorkerTaskId() == null) {
            throw new IllegalStateException("No worker task ID, cannot resync");
        }

        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        persistTask(entity);
        log.info("Resync: reset task {} to RUNNING, attempting SSE reconnect", taskId);

        try {
            streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
        } catch (Exception e) {
            log.warn("Resync: SSE reconnect failed for task {}: {}", taskId, e.getMessage());
        }

        return Map.of("status", "RESYNCED", "action", "RECONNECTED", "taskId", taskId);
    }

    private TaskPageResult buildSessionPage(List<CodexTaskEntity> tasks, int page, int size, String interactionState) {
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

        return TaskPageResult.of(content, totalSessions, page, size);
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
                || "ABORTED".equals(taskStatus) || "AWAITING_PERMISSION".equals(taskStatus)
                || "AWAITING_INPUT".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private void publishStatusChange(CodexTaskEntity entity, String previousStatus) {
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(firstNonBlank(resolveLogicalAgentId(entity), resolveProviderType(entity)))
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
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(entity.getTaskId())
                .orElseGet(SessionTaskEntity::new);
        String agentId = firstNonBlank(
                entity.getResolvedAgentId(),
                sessionTask.getAgentId(),
                resolveSessionAgentId(entity.getSessionId()));
        String providerType = firstNonBlank(
                entity.getProviderType(),
                sessionTask.getProviderType(),
                resolveSessionProviderType(entity.getSessionId()),
                AGENT_ID);
        entity.setProviderType(providerType);
        sessionTask.setTaskId(entity.getTaskId());
        sessionTask.setSessionId(entity.getSessionId());
        sessionTask.setProviderType(providerType);
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
        sessionTask.setLastOutputAt(entity.getLastOutputAt());
        sessionTask.setCreatedAt(entity.getCreatedAt());
        sessionTask.setUpdatedAt(entity.getUpdatedAt());
        sessionTask.setTaskStateJson(buildCodexTaskStateJson(entity, sessionTask.getTaskStateJson()));
        sessionTaskRepository.save(sessionTask);
    }

    private void syncSessionProjection(CodexTaskEntity entity) {
        if (sessionEntityRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        String agentId = resolveLogicalAgentId(entity);
        String providerType = resolveProviderType(entity);

        SessionEntity session = sessionEntityRepository.findById(entity.getSessionId())
                .orElseGet(() -> createSessionProjection(entity));
        session.setUserId(firstNonBlank(session.getUserId(), entity.getUserId()));
        session.setTenantId(firstNonBlank(session.getTenantId(), entity.getTenantId()));
        session.setAgentId(firstNonBlank(session.getAgentId(), agentId));
        validateSessionProviderAffinity(session, providerType);
        session.setProviderType(providerType);
        session.setStatus(firstNonBlank(session.getStatus(), "ACTIVE"));
        session.setCurrentWorkerId(firstNonBlank(entity.getWorkerId(), session.getCurrentWorkerId()));
        session.setCurrentDirectoryId(firstNonBlank(entity.getDirectoryId(), session.getCurrentDirectoryId()));
        session.setLatestTaskId(entity.getTaskId());
        session.setLatestModel(firstNonBlank(entity.getModel(), session.getLatestModel()));
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        Map<String, Object> providerStateValues = new LinkedHashMap<>();
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_THREAD_ID, entity.getCodexThreadId());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, entity.getRuntimeId());
        putIfNotNull(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION,
                entity.getRuntimeRevision());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, entity.getRuntimeType());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID,
                entity.getRuntimeInstanceId());
        putIfNotNull(providerStateValues, ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, entity.getRoutingEpoch());
        if (isCodexBizProvider(providerType)) {
            putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_HOME_KEY, entity.getCodexHomeKey());
            putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID,
                    firstNonBlank(entity.getPrivateAccountId(), entity.getCodexHomeKey()));
        }
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                session.getProviderStateJson(),
                providerType,
                providerStateValues));
        sessionEntityRepository.save(session);
    }

    private SessionEntity createSessionProjection(CodexTaskEntity entity) {
        String agentId = resolveLogicalAgentId(entity);
        String providerType = resolveProviderType(entity);
        SessionEntity session = new SessionEntity();
        session.setId(entity.getSessionId());
        session.setUserId(entity.getUserId());
        session.setTenantId(entity.getTenantId());
        session.setAgentId(agentId);
        session.setProviderType(providerType);
        session.setStatus("ACTIVE");
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        session.setCurrentWorkerId(entity.getWorkerId());
        session.setCurrentDirectoryId(entity.getDirectoryId());
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        return session;
    }

    private String buildCodexTaskStateJson(CodexTaskEntity entity, String existingJson) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_THREAD_ID, entity.getCodexThreadId());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CONTEXT_ID, entity.getContextId());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, entity.getRuntimeId());
        putIfNotNull(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, entity.getRuntimeRevision());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, entity.getRuntimeType());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID, entity.getRuntimeInstanceId());
        putIfNotNull(state, ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, entity.getRoutingEpoch());
        putIfNotBlank(state, ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE,
                entity.getRuntimeAcceptanceState());
        return ProviderStateCodec.mergeTaskValues(existingJson, resolveProviderType(entity), state);
    }

    private String resolveTaskContextId(CodexTaskEntity entity) {
        if (entity.getContextId() != null && !entity.getContextId().isBlank()) {
            return entity.getContextId();
        }
        if (sessionTaskRepository == null || entity.getTaskId() == null || entity.getTaskId().isBlank()) {
            return null;
        }
        return sessionTaskRepository.findByTaskId(entity.getTaskId())
                .map(SessionTaskEntity::getTaskStateJson)
                .map(json -> ProviderStateCodec.readStringOrNull(json, ProviderStateCodec.FIELD_CONTEXT_ID))
                .filter(contextId -> contextId != null && !contextId.isBlank())
                .orElse(null);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private CodexRuntimeBinding resolveRuntimeBinding(String workerId, String model, String providerType,
                                                       String routingKey, String existingSessionId) {
        return resolveRuntimeBinding(workerId, model, providerType, routingKey, existingSessionId, Set.of());
    }

    private CodexRuntimeBinding resolveRuntimeBinding(String workerId, String model, String providerType,
                                                       String routingKey, String existingSessionId,
                                                       Set<String> requiredFeatures) {
        boolean appServerProvider = isCodexAppServerProvider(providerType);
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            CodexRuntimeBinding sessionBinding = resolveExistingSessionBinding(existingSessionId, workerId);
            if (sessionBinding != null) {
                validateProviderRuntimeBinding(providerType, sessionBinding);
                if (appServerProvider) {
                    runtimeRegistryService.validateBoundRuntimeCapabilities(
                            sessionBinding, model, requiredFeatures);
                }
                return sessionBinding;
            }
            if (appServerProvider) {
                throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                        "App-server session has no immutable runtime affinity");
            }
            return CodexRuntimeBinding.legacySdk(workerId);
        }
        if (!appServerProvider) {
            return CodexRuntimeBinding.legacySdk(workerId);
        }
        if (runtimeRegistryService == null) {
            String code = isUltraModel(model)
                    ? "CODEX_ULTRA_RUNTIME_UNAVAILABLE" : "CODEX_RUNTIME_UNAVAILABLE";
            throw new CodexRuntimeUnavailableException(code,
                    "Runtime registry is unavailable for the app-server provider");
        }
        CodexRuntimeBinding binding = runtimeRegistryService.selectForNewTask(
                workerId, model, providerType, routingKey, requiredFeatures);
        validateProviderRuntimeBinding(providerType, binding);
        return binding;
    }

    private Set<String> runtimeRequirements(CreateCodexTaskForm form, String providerType) {
        Set<String> features = new LinkedHashSet<>();
        if (form.getImages() != null && !form.getImages().isBlank()) features.add("images");
        if (form.getAttachments() != null && !form.getAttachments().isEmpty()) features.add("attachments");
        if (form.getOutputSchema() != null && !form.getOutputSchema().isEmpty()) features.add("output_schema");
        if (form.getDeveloperInstructions() != null && !form.getDeveloperInstructions().isBlank()) {
            features.add("developer_instructions");
        }
        if (form.getSandboxMode() != null && !form.getSandboxMode().isBlank()) features.add("sandbox");
        if (form.getNetworkAccessEnabled() != null) features.add("network");
        if (form.getWebSearchMode() != null && !form.getWebSearchMode().isBlank()) features.add("web");
        if (form.getAdditionalDirectories() != null && !form.getAdditionalDirectories().isEmpty()) {
            features.add("additional_directories");
        }
        if (form.getMaxTurns() != null && form.getMaxTurns() > 1) features.add("max_turns");
        if (form.getApprovalPolicy() != null && !form.getApprovalPolicy().isBlank()) {
            features.add("approval:" + form.getApprovalPolicy());
        }
        if (isCodexBizProvider(providerType)
                || (form.getBusinessRuntimeContext() != null && !form.getBusinessRuntimeContext().isEmpty())) {
            features.add("business_mcp");
        }
        return features;
    }

    private CodexRuntimeBinding resolveExistingSessionBinding(String sessionId, String workerId) {
        String legacySessionWorkerId = null;
        if (sessionEntityRepository != null) {
            SessionEntity session = sessionEntityRepository.findById(sessionId).orElse(null);
            if (session != null) {
                Map<String, Object> state = ProviderStateCodec.parseObject(session.getProviderStateJson());
                CodexRuntimeBinding binding = bindingFromState(state, workerId);
                if (binding != null) return binding;
                legacySessionWorkerId = session.getCurrentWorkerId();
            }
        }
        CodexRuntimeBinding taskBinding = taskRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(task -> bindingFromTask(task, workerId))
                .orElse(null);
        if (taskBinding != null) return taskBinding;
        if (legacySessionWorkerId != null && !legacySessionWorkerId.isBlank()) {
            validateLegacyWorkerAffinity(legacySessionWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(legacySessionWorkerId);
        }
        return null;
    }

    private CodexRuntimeBinding resolveThreadRuntimeBinding(String codexThreadId, String workerId,
                                                             String userId, String providerType) {
        CodexRuntimeBinding existing = taskRepository
                .findFirstByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeOrderByCreatedAtDesc(
                        codexThreadId, workerId, userId, providerType)
                .map(task -> bindingFromTask(task, workerId))
                .orElse(null);
        // Threads created before affinity columns were introduced belong to the
        // legacy SDK lane and must not move during rollout.
        return existing != null ? existing : CodexRuntimeBinding.legacySdk(workerId);
    }

    private CodexRuntimeBinding bindingFromState(Map<String, Object> state, String workerId) {
        String runtimeId = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_ID));
        String runtimeType = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE));
        if (runtimeId == null && runtimeType == null) return null;
        if (CodexRuntimeType.SDK_EXEC.name().equals(runtimeType)
                || (runtimeId != null && runtimeId.startsWith("legacy-sdk:"))) {
            if (runtimeId == null || !runtimeId.startsWith("legacy-sdk:")) {
                // Pre-affinity state must fall back to the latest persisted task so its
                // original physical Worker remains authoritative.
                return null;
            }
            String boundWorkerId = runtimeId.substring("legacy-sdk:".length());
            validateLegacyWorkerAffinity(boundWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(boundWorkerId);
        }
        if (runtimeId == null || runtimeRegistryService == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Session runtime affinity is incomplete: " + runtimeId);
        }
        Integer revision = integerValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION));
        String instanceId = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID));
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                runtimeId, revision, workerId, instanceId);
        validateBoundWorker(binding, workerId);
        return binding;
    }

    private CodexRuntimeBinding bindingFromTask(CodexTaskEntity task, String workerId) {
        if (task.getRuntimeId() == null || task.getRuntimeId().isBlank()) return null;
        if (CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())
                || task.getRuntimeId().startsWith("legacy-sdk:")) {
            String boundWorkerId = task.getWorkerId();
            if (task.getRuntimeId().startsWith("legacy-sdk:")) {
                String encodedWorkerId = task.getRuntimeId().substring("legacy-sdk:".length());
                if (!encodedWorkerId.equals(boundWorkerId)) {
                    throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                            "Legacy SDK runtime and task worker affinity disagree");
                }
            }
            validateLegacyWorkerAffinity(boundWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(boundWorkerId);
        }
        if (runtimeRegistryService == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                    "Runtime registry is unavailable for bound session " + task.getSessionId());
        }
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), workerId, task.getRuntimeInstanceId());
        validateBoundWorker(binding, workerId);
        return binding;
    }

    private void validateBoundWorker(CodexRuntimeBinding binding, String workerId) {
        if (binding.getWorkerId() != null && !binding.getWorkerId().equals(workerId)) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Bound runtime belongs to another worker");
        }
    }

    private void validateLegacyWorkerAffinity(String boundWorkerId, String requestedWorkerId) {
        if (boundWorkerId == null || boundWorkerId.isBlank()) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Legacy SDK runtime affinity has no worker");
        }
        if (requestedWorkerId != null && !requestedWorkerId.equals(boundWorkerId)) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Legacy SDK runtime belongs to another worker");
        }
    }

    private void applyRuntimeBinding(CodexTaskEntity entity, CodexRuntimeBinding binding) {
        validateBoundWorker(binding, entity.getWorkerId());
        validateProviderRuntimeBinding(entity.getProviderType(), binding);
        entity.setRuntimeId(binding.getRuntimeId());
        entity.setRuntimeRevision(binding.getRuntimeRevision());
        entity.setRuntimeType(binding.getRuntimeType().name());
        entity.setRuntimeInstanceId(binding.getInstanceId());
        entity.setRoutingEpoch(binding.getRoutingEpoch());
        if (binding.getRuntimeType() == CodexRuntimeType.APP_SERVER) {
            entity.setRuntimeAcceptanceState("PREPARED");
        }
    }

    private boolean isUltraModel(String model) {
        if (model == null) return false;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return "codex-ultra".equals(normalized) || normalized.endsWith(":ultra");
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private void requireUserInputPersistence() {
        if (sessionTaskRepository == null) {
            throw interactionError("CODEX_USER_INPUT_STATE_UNAVAILABLE");
        }
        if (runtimeRegistryService == null) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST");
        }
    }

    private SessionTaskEntity requireSessionTask(CodexTaskEntity entity) {
        return sessionTaskRepository.findByTaskIdForUpdate(entity.getTaskId())
                .orElseThrow(() -> interactionError("CODEX_USER_INPUT_STATE_MISSING"));
    }

    private Map<String, Object> pendingUserInput(SessionTaskEntity sessionTask) {
        Object value = ProviderStateCodec.parseObject(sessionTask.getTaskStateJson()).get(USER_INPUT_STATE_KEY);
        return objectMap(value);
    }

    private void savePendingUserInput(SessionTaskEntity sessionTask, CodexTaskEntity entity,
                                      Map<String, Object> pending) {
        sessionTask.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                sessionTask.getTaskStateJson(), resolveProviderType(entity), USER_INPUT_STATE_KEY, pending));
        sessionTaskRepository.save(sessionTask);
    }

    private void markPendingResolved(SessionTaskEntity sessionTask, CodexTaskEntity entity,
                                     Map<String, Object> pending, String reason) {
        Map<String, Object> resolved = new LinkedHashMap<>(pending);
        resolved.put("state", "RESOLVED");
        resolved.put("resolved_reason", reason);
        resolved.put("resolved_at", LocalDateTime.now().toString());
        savePendingUserInput(sessionTask, entity, resolved);
    }

    private Map<String, Object> sanitizePendingUserInput(CodexTaskEntity entity,
                                                         Map<String, Object> projection) {
        Map<String, Object> source = objectMap(projection != null
                ? projection.get("pending_interaction") : null);
        if (source.isEmpty()) {
            source = projection != null ? new LinkedHashMap<>(projection) : Map.of();
        }
        if (!Integer.valueOf(1).equals(integerValue(source.get("contract_version")))) {
            throw interactionError("CODEX_USER_INPUT_CONTRACT_UNSUPPORTED");
        }
        String method = boundedRequiredString(source.get("method"), "method", 128);
        if (!USER_INPUT_METHOD.equals(method)) {
            throw interactionError("CODEX_USER_INPUT_METHOD_UNSUPPORTED");
        }
        Object requestId = sanitizeRequestId(source.get("request_id"));
        String threadId = boundedRequiredString(source.get("thread_id"), "thread_id", 256);
        String turnId = boundedRequiredString(source.get("turn_id"), "turn_id", 256);
        String itemId = boundedRequiredString(source.get("item_id"), "item_id", 256);
        String runtimeInstanceId = boundedRequiredString(
                source.get("runtime_instance_id"), "runtime_instance_id", 128);
        if (entity.getCodexThreadId() != null && !entity.getCodexThreadId().isBlank()
                && !entity.getCodexThreadId().equals(threadId)) {
            throw interactionError("CODEX_USER_INPUT_THREAD_MISMATCH");
        }

        Object rawQuestions = source.get("questions");
        if (!(rawQuestions instanceof List<?> questions)
                || questions.isEmpty() || questions.size() > MAX_USER_INPUT_QUESTIONS) {
            throw interactionError("CODEX_USER_INPUT_QUESTIONS_INVALID");
        }
        List<Map<String, Object>> sanitizedQuestions = new ArrayList<>();
        Set<String> questionIds = new LinkedHashSet<>();
        for (Object rawQuestion : questions) {
            Map<String, Object> question = objectMap(rawQuestion);
            String id = boundedRequiredString(question.get("id"), "question.id", 256);
            if (!questionIds.add(id)) {
                throw interactionError("CODEX_USER_INPUT_QUESTION_ID_DUPLICATE");
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("id", id);
            sanitized.put("header", boundedRequiredString(question.get("header"), "question.header", 64));
            sanitized.put("question", boundedRequiredDisplayString(
                    question.get("question"), "question.question", 4_096));
            List<Map<String, Object>> options = sanitizeUserInputOptions(question.get("options"));
            if (!options.isEmpty()) {
                sanitized.put("options", options);
            }
            sanitized.put("is_other", booleanValue(question.get("is_other")));
            sanitized.put("is_secret", booleanValue(question.get("is_secret")));
            sanitizedQuestions.add(sanitized);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contract_version", 1);
        result.put("request_id", requestId);
        result.put("method", method);
        result.put("thread_id", threadId);
        result.put("turn_id", turnId);
        result.put("item_id", itemId);
        result.put("questions", sanitizedQuestions);
        Integer autoResolutionMs = boundedAutoResolutionMs(source.get("auto_resolution_ms"));
        if (autoResolutionMs != null) {
            result.put("auto_resolution_ms", autoResolutionMs);
        }
        result.put("runtime_instance_id", runtimeInstanceId);
        String createdAt = boundedOptionalString(source.get("created_at"), "created_at", 64);
        if (createdAt != null) {
            result.put("created_at", createdAt);
        }
        return result;
    }

    private List<Map<String, Object>> sanitizeUserInputOptions(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> options) || options.size() > 20) {
            throw interactionError("CODEX_USER_INPUT_OPTIONS_INVALID");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawOption : options) {
            Map<String, Object> option = objectMap(rawOption);
            if (option.isEmpty() && rawOption instanceof String label) {
                option = Map.of("label", label);
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("label", boundedRequiredString(option.get("label"), "option.label", 256));
            sanitized.put("description", boundedAllowEmptyString(
                    option.get("description"), "option.description", 2_048));
            result.add(sanitized);
        }
        return result;
    }

    private Map<String, Object> confirmationPayload(String taskId, Map<String, Object> pending) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", externalRequestToken(taskId, pending.get("request_id")));
        payload.put("interactionType", "user_input");
        payload.put("method", pending.get("method"));
        List<Map<String, Object>> projectedQuestions = new ArrayList<>();
        Object rawQuestions = pending.get("questions");
        if (rawQuestions instanceof List<?> questions) {
            for (Object rawQuestion : questions) {
                Map<String, Object> question = objectMap(rawQuestion);
                Map<String, Object> projected = new LinkedHashMap<>();
                projected.put("id", question.get("id"));
                projected.put("header", question.get("header"));
                projected.put("question", question.get("question"));
                if (question.containsKey("options")) {
                    projected.put("options", question.get("options"));
                }
                projected.put("isOther", booleanValue(question.get("is_other")));
                projected.put("isSecret", booleanValue(question.get("is_secret")));
                projected.put("multiSelect", false);
                projectedQuestions.add(projected);
            }
        }
        payload.put("questions", projectedQuestions);
        if (pending.containsKey("auto_resolution_ms")) {
            payload.put("autoResolutionMs", pending.get("auto_resolution_ms"));
        }
        return payload;
    }

    private NormalizedUserInputAnswers normalizeUserInputAnswers(Map<String, Object> pending,
                                                                  Object rawAnswers) {
        if (!(rawAnswers instanceof Map<?, ?> answerMap)) {
            throw interactionError("CODEX_USER_INPUT_ANSWERS_INVALID");
        }
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Object rawQuestions = pending.get("questions");
        if (rawQuestions instanceof List<?> questions) {
            for (Object rawQuestion : questions) {
                Map<String, Object> question = objectMap(rawQuestion);
                expected.put(stringValue(question.get("id")), booleanValue(question.get("is_secret")));
            }
        }
        Map<String, Object> supplied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : answerMap.entrySet()) {
            if (!(entry.getKey() instanceof String key) || supplied.put(key, entry.getValue()) != null) {
                throw interactionError("CODEX_USER_INPUT_ANSWERS_INVALID");
            }
        }
        if (!supplied.keySet().equals(expected.keySet())) {
            throw interactionError("CODEX_USER_INPUT_QUESTION_MISMATCH");
        }

        Map<String, List<String>> workerAnswers = new LinkedHashMap<>();
        Map<String, String> historyAnswers = new LinkedHashMap<>();
        boolean containsSecret = false;
        for (Map.Entry<String, Boolean> question : expected.entrySet()) {
            String answer = singleUserInputAnswer(supplied.get(question.getKey()));
            workerAnswers.put(question.getKey(), List.of(answer));
            historyAnswers.put(question.getKey(), answer);
            containsSecret |= question.getValue();
        }
        return new NormalizedUserInputAnswers(workerAnswers, historyAnswers, containsSecret);
    }

    private String singleUserInputAnswer(Object value) {
        String answer;
        if (value instanceof String text) {
            answer = text;
        } else if (value instanceof Collection<?> values && values.size() == 1
                && values.iterator().next() instanceof String text) {
            answer = text;
        } else {
            throw interactionError("CODEX_USER_INPUT_ANSWER_CARDINALITY_INVALID");
        }
        if (answer.isEmpty() || answer.length() > 16_384) {
            throw interactionError("CODEX_USER_INPUT_ANSWER_INVALID");
        }
        return answer;
    }

    private void validateUserInputResponse(Map<String, Object> result, String workerTaskId,
                                           Object wireRequestId) {
        if (result == null
                || !workerTaskId.equals(stringValue(result.get("task_id")))
                || !"running".equalsIgnoreCase(stringValue(result.get("status")))
                || !sameWireRequestId(wireRequestId, result.get("request_id"))) {
            throw interactionError("CODEX_USER_INPUT_RESPONSE_INVALID");
        }
    }

    private CodexWorkerClient.UserInputResponseException findUserInputResponseError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexWorkerClient.UserInputResponseException responseError) {
                return responseError;
            }
            current = current.getCause();
        }
        return null;
    }

    private CodexWorkerClient.RuntimeInstanceProofException findRuntimeProofError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexWorkerClient.RuntimeInstanceProofException proofError) {
                return proofError;
            }
            current = current.getCause();
        }
        return null;
    }

    private Object sanitizeRequestId(Object value) {
        if (value instanceof String text && !text.isBlank() && text.length() <= 256
                && text.chars().noneMatch(Character::isISOControl)) {
            return text;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number >= -9_007_199_254_740_991L && number <= 9_007_199_254_740_991L) {
                return number;
            }
        }
        throw interactionError("CODEX_USER_INPUT_REQUEST_ID_INVALID");
    }

    private Integer boundedAutoResolutionMs(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw interactionError("CODEX_USER_INPUT_AUTO_RESOLUTION_INVALID");
        }
        long milliseconds = ((Number) value).longValue();
        if (milliseconds < 60_000L || milliseconds > 240_000L) {
            throw interactionError("CODEX_USER_INPUT_AUTO_RESOLUTION_INVALID");
        }
        return (int) milliseconds;
    }

    private String externalRequestToken(String taskId, Object value) {
        Object sanitized = sanitizeRequestId(value);
        String typed = sanitized instanceof String text ? "string:" + text : "number:" + sanitized;
        return "task:" + taskId + ":" + typed;
    }

    private boolean sameWireRequestId(Object left, Object right) {
        try {
            Object normalizedLeft = sanitizeRequestId(left);
            Object normalizedRight = sanitizeRequestId(right);
            return normalizedLeft.getClass().equals(normalizedRight.getClass())
                    && normalizedLeft.equals(normalizedRight);
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private String boundedRequiredString(Object value, String field, int maxLength) {
        String text = boundedOptionalString(value, field, maxLength);
        if (text == null) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedOptionalString(Object value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedRequiredDisplayString(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(this::isUnsafeDisplayControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedAllowEmptyString(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.length() > maxLength
                || text.chars().anyMatch(this::isUnsafeDisplayControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private boolean isUnsafeDisplayControl(int character) {
        return Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t';
    }

    private boolean samePendingUserInput(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> existingContract = new LinkedHashMap<>(existing);
        Map<String, Object> incomingContract = new LinkedHashMap<>(incoming);
        for (String transientKey : List.of(
                "state", "resolved_reason", "resolved_at", "request_id")) {
            existingContract.remove(transientKey);
            incomingContract.remove(transientKey);
        }
        return existingContract.equals(incomingContract);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> {
            if (key != null) {
                result.put(key.toString(), entryValue);
            }
        });
        return result;
    }

    private IllegalStateException interactionError(String code) {
        return new IllegalStateException(code);
    }

    private IllegalStateException interactionError(String code, Throwable cause) {
        return new IllegalStateException(code, cause);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachmentsParam(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
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

    private String normalizeProviderType(String providerType) {
        String normalized = firstNonBlank(providerType, CODEX_PROVIDER_TYPE);
        if (CODEX_PROVIDER_TYPE.equals(normalized)
                || CODEX_APP_SERVER_PROVIDER_TYPE.equals(normalized)
                || CODEX_BIZ_PROVIDER_TYPE.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Codex providerType: " + normalized);
    }

    private String requireProviderParams(String expectedProviderType, Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("Codex task params are required");
        }
        String expected = normalizeProviderType(expectedProviderType);
        for (String key : List.of("providerType", "provider_type")) {
            String requested = stringParam(params, key);
            if (requested != null && !expected.equals(normalizeProviderType(requested))) {
                throw new IllegalArgumentException("CODEX_TASK_PROVIDER_MISMATCH: provider route "
                        + expected + " cannot be overridden by " + requested);
            }
        }
        return expected;
    }

    private void requireTaskProvider(CodexTaskEntity entity, String expectedProviderType) {
        String expected = normalizeProviderType(expectedProviderType);
        if (entity == null || !expected.equals(resolveProviderType(entity))) {
            throw new IllegalArgumentException("Task not found: "
                    + (entity != null ? entity.getTaskId() : null));
        }
    }

    private boolean isCodexBizProvider(String providerType) {
        return CODEX_BIZ_PROVIDER_TYPE.equals(providerType);
    }

    private boolean isCodexAppServerProvider(String providerType) {
        return CODEX_APP_SERVER_PROVIDER_TYPE.equals(providerType);
    }

    private void validateModelConfigProvider(@Nullable String modelConfigId,
                                             @Nullable LlmModelConfigDTO modelConfig,
                                             String executionProviderType) {
        if (modelConfig == null) {
            return;
        }
        String backend = firstNonBlank(modelConfig.getWorkerBackend());
        if (backend == null) {
            if (isCodexAppServerProvider(executionProviderType)) {
                throw new IllegalArgumentException("MODEL_CONFIG_PROVIDER_MISMATCH: model config '"
                        + modelConfigId + "' has no app-server workerBackend");
            }
            return;
        }
        String modelProviderType = ProviderRouteRegistry.providerTypeForWorkerBackend(backend)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported workerBackend for model config '" + modelConfigId + "': " + backend));
        if (!ProviderRouteRegistry.isModelProviderCompatible(modelProviderType, executionProviderType)) {
            throw new IllegalArgumentException("MODEL_CONFIG_PROVIDER_MISMATCH: model config '"
                    + modelConfigId + "' routes to " + modelProviderType
                    + " but task routes to " + executionProviderType);
        }
    }

    private void validateProviderModel(String providerType, @Nullable String model) {
        if (!isCodexAppServerProvider(providerType) && isUltraModel(model)) {
            throw new IllegalArgumentException(
                    "CODEX_ULTRA_APP_SERVER_REQUIRED: Ultra requires codex-app-server-worker");
        }
    }

    private void validateProviderRuntimeBinding(String providerType, CodexRuntimeBinding binding) {
        if (binding == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                    "Codex runtime binding is missing");
        }
        boolean appBinding = binding.getRuntimeType() == CodexRuntimeType.APP_SERVER;
        if (isCodexAppServerProvider(providerType) != appBinding) {
            throw new CodexRuntimeUnavailableException("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "Provider " + providerType + " cannot execute on runtime " + binding.getRuntimeType());
        }
    }

    private void validateExistingSessionProvider(String sessionId, String requestedProviderType) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionEntityRepository.findById(sessionId).ifPresent(session ->
                validateSessionProviderAffinity(session, requestedProviderType));
    }

    private void validateSessionProviderAffinity(SessionEntity session, String requestedProviderType) {
        String existingProviderType = firstNonBlank(session.getProviderType());
        if (existingProviderType != null && !existingProviderType.equals(requestedProviderType)) {
            throw new IllegalArgumentException("SESSION_PROVIDER_MISMATCH: session " + session.getId()
                    + " is bound to " + existingProviderType
                    + " and cannot continue with " + requestedProviderType);
        }
    }

    private void normalizeAndValidateCodexBizHomeKey(CreateCodexTaskForm form, String providerType) {
        if (!isCodexBizProvider(providerType)) {
            return;
        }
        String codexHomeKey = firstNonBlank(form.getCodexHomeKey(), form.getPrivateAccountId());
        if (codexHomeKey == null) {
            throw new IllegalArgumentException("codex-biz-worker requires codexHomeKey or privateAccountId");
        }
        form.setCodexHomeKey(codexHomeKey);
    }

    private boolean matchesProvider(CodexTaskEntity entity, String providerType) {
        return normalizeProviderType(providerType).equals(resolveProviderType(entity));
    }

    private List<CodexTaskEntity> filterTasksByProvider(List<CodexTaskEntity> tasks, String providerType) {
        return tasks.stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .toList();
    }

    private String resolveProviderType(CodexTaskEntity entity) {
        if (entity == null) {
            return AGENT_ID;
        }
        String providerType = firstNonBlank(
                entity.getProviderType(),
                resolveSessionTaskProviderType(entity.getTaskId()),
                resolveSessionProviderType(entity.getSessionId()),
                AGENT_ID);
        providerType = normalizeProviderType(providerType);
        entity.setProviderType(providerType);
        return providerType;
    }

    private Map<String, String> resolveProviderTypes(List<CodexTaskEntity> entities) {
        Map<String, String> taskProviders = new LinkedHashMap<>();
        Set<String> unresolvedTaskIds = entities.stream()
                .filter(entity -> firstNonBlank(entity.getProviderType()) == null)
                .map(CodexTaskEntity::getTaskId)
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sessionTaskRepository != null && !unresolvedTaskIds.isEmpty()) {
            sessionTaskRepository.findByTaskIdIn(unresolvedTaskIds).forEach(sessionTask -> {
                String providerType = firstNonBlank(sessionTask.getProviderType());
                if (providerType != null) {
                    taskProviders.put(sessionTask.getTaskId(), providerType);
                }
            });
        }

        Set<String> unresolvedSessionIds = entities.stream()
                .filter(entity -> firstNonBlank(
                        entity.getProviderType(), taskProviders.get(entity.getTaskId())) == null)
                .map(CodexTaskEntity::getSessionId)
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> sessionProviders = new LinkedHashMap<>();
        if (sessionEntityRepository != null && !unresolvedSessionIds.isEmpty()) {
            sessionEntityRepository.findAllById(unresolvedSessionIds).forEach(session -> {
                String providerType = firstNonBlank(session.getProviderType());
                if (providerType != null) {
                    sessionProviders.put(session.getId(), providerType);
                }
            });
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        entities.forEach(entity -> {
            String providerType = normalizeProviderType(firstNonBlank(
                    entity.getProviderType(),
                    taskProviders.get(entity.getTaskId()),
                    sessionProviders.get(entity.getSessionId()),
                    AGENT_ID));
            entity.setProviderType(providerType);
            resolved.put(entity.getTaskId(), providerType);
        });
        return resolved;
    }

    private String resolveSessionTaskProviderType(String taskId) {
        if (sessionTaskRepository == null || taskId == null || taskId.isBlank()) {
            return null;
        }
        return sessionTaskRepository.findByTaskId(taskId)
                .map(SessionTaskEntity::getProviderType)
                .filter(providerType -> providerType != null && !providerType.isBlank())
                .orElse(null);
    }

    private String resolveSessionProviderType(String sessionId) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionEntityRepository.findById(sessionId)
                .map(SessionEntity::getProviderType)
                .filter(providerType -> providerType != null && !providerType.isBlank())
                .orElse(null);
    }

    private void applyCodexBizParams(CreateCodexTaskForm form, Map<String, Object> params) {
        Map<String, Object> policy = mapParam(firstPresent(params, "codexPolicy", "codex_policy"));
        form.setCodexHomeKey(firstNonBlank(
                stringParam(params, "codexHomeKey"),
                stringParam(params, "codex_home_key"),
                stringParam(params, "privateAccountId"),
                stringParam(params, "private_account_id")));
        form.setDeveloperInstructions(firstNonBlank(
                stringParam(params, "developerInstructions"),
                stringParam(params, "developer_instructions")));
        form.setBusinessRuntimeContext(mapParam(firstPresent(params,
                "businessRuntimeContext", "business_runtime_context", "runtimeContext", "runtime_context")));
        form.setOutputSchema(mapParam(firstPresent(params,
                "outputSchema", "output_schema", "expectedOutputSchema", "expected_output_schema")));
        form.setCodexConfig(mapParam(firstPresent(params, "codexConfig", "codex_config")));
        form.setSandboxMode(firstNonBlank(
                stringParam(params, "sandboxMode"),
                stringParam(params, "sandbox_mode"),
                stringMap(policy, "sandboxMode"),
                stringMap(policy, "sandbox_mode")));
        form.setApprovalPolicy(firstNonBlank(
                stringParam(params, "approvalPolicy"),
                stringParam(params, "approval_policy"),
                stringMap(policy, "approvalPolicy"),
                stringMap(policy, "approval_policy")));
        form.setNetworkAccessEnabled(firstNonNull(
                booleanParam(firstPresent(params, "networkAccessEnabled", "network_access_enabled")),
                booleanParam(firstPresent(policy, "networkAccessEnabled", "network_access_enabled"))));
        form.setWebSearchMode(firstNonBlank(
                stringParam(params, "webSearchMode"),
                stringParam(params, "web_search_mode"),
                stringMap(policy, "webSearchMode"),
                stringMap(policy, "web_search_mode")));
        form.setAdditionalDirectories(stringListParam(firstPresent(params, "additionalDirectories", "additional_directories")));
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapParam(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String stringMap(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private Boolean booleanParam(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private List<String> stringListParam(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .toList();
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
        if (sessionEntityRepository != null) {
            SessionEntity entity = sessionEntityRepository.findById(sessionId).orElse(null);
            if (entity != null) {
                if (!userId.equals(entity.getUserId())) {
                    throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
                }
                return;
            }
        }
        if (sessionManager != null) {
            Session session = sessionManager.getSession(sessionId);
            if (session != null && userId.equals(session.getUserId())) {
                return;
            }
        }
        throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
    }

    private void lockExistingSessionForResume(String userId, String sessionId) {
        if (sessionEntityRepository == null) return;
        sessionEntityRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found or access denied: " + sessionId));
    }

    @Override
    @Transactional
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        return rewindTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId, params);
    }

    @Transactional
    public Object rewindTaskForProvider(String providerType, String taskId, String userId,
                                        Map<String, Object> params) {
        CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        requireTaskProvider(task, providerType);
        if ("RUNNING".equals(task.getStatus()) || "AWAITING_PERMISSION".equals(task.getStatus())
                || "AWAITING_INPUT".equals(task.getStatus())) {
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
            session.setProviderStateJson(ProviderStateCodec.mergeSessionValue(
                    session.getProviderStateJson(),
                    firstNonBlank(session.getProviderType(), CODEX_PROVIDER_TYPE),
                    ProviderStateCodec.FIELD_CODEX_THREAD_ID,
                    null));
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
                                     String existingSessionId, String agentId, String providerType) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return createPlatformSession(userId, tenantId, prompt, agentId, providerType);
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
    private String createPlatformSession(String userId, String tenantId, String prompt,
                                         String agentId, String providerType) {
        if (sessionManager == null) {
            log.warn("SessionManager not available, falling back to IdGenerator for Codex sessionId");
            return IdGenerator.shortId();
        }
        String title = prompt != null && prompt.length() > 100 ? prompt.substring(0, 100) : prompt;
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(agentId)
                .providerType(providerType)
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

    private LlmModelConfigDTO validateAndResolveModelConfig(@Nullable String modelConfigId, String workerId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        if (llmModelManager == null) {
            throw new IllegalStateException(
                    "LLM model manager is unavailable for modelConfigId=" + modelConfigId);
        }
        llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
        return llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "LLM model config not found: " + modelConfigId));
    }

    private ModelResolution resolveEffectiveModel(String explicitModel, @Nullable String agentId,
                                                   @Nullable LlmModelConfigDTO modelConfig) {
        if (explicitModel != null && !explicitModel.isBlank()) {
            return new ModelResolution(explicitModel, "request");
        }
        if (agentId != null && !agentId.isBlank() && codingAgentRepository != null) {
            CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
            if (agentEntity != null && agentEntity.getDefaultModel() != null && !agentEntity.getDefaultModel().isBlank()) {
                return new ModelResolution(agentEntity.getDefaultModel(), "agent-default");
            }
        }
        if (modelConfig == null) {
            return new ModelResolution(null, "none");
        }
        String model = modelConfig.getModelName();
        if (model != null && model.isBlank()) {
            model = null;
        }
        return new ModelResolution(model, model != null ? "model-config" : "none");
    }

    private void validateEffectiveModelGrant(@Nullable String model, @Nullable String modelConfigId,
                                             @Nullable LlmModelConfigDTO modelConfig) {
        if (model == null || model.isBlank()) {
            return;
        }
        String requestedModel = model.trim();
        if (isUnsupportedKnownCodexCatalogModel(requestedModel)) {
            throw new IllegalArgumentException("Codex model '" + requestedModel
                    + "' is not supported by the current model catalog");
        }
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return;
        }
        String normalizedModel = requestedModel.toLowerCase(Locale.ROOT);
        Optional<String> catalogModel = normalizeKnownCodexCatalogModel(requestedModel);
        boolean futureGatedModel = catalogModel.isEmpty() && isGatedCodexModel(normalizedModel);
        if (catalogModel.isEmpty() && !futureGatedModel) {
            return;
        }

        List<String> availableModels = modelConfig != null ? modelConfig.getAvailableModels() : null;
        if (availableModels == null || availableModels.isEmpty()) {
            return;
        }
        boolean granted = availableModels.stream()
                .filter(allowedModel -> allowedModel != null && !allowedModel.isBlank())
                .map(String::trim)
                .anyMatch(allowedModel -> catalogModel
                        .map(value -> normalizeKnownCodexCatalogModel(allowedModel)
                                .map(value::equals)
                                .orElse(false))
                        .orElseGet(() -> requestedModel.equals(allowedModel)));
        if (!granted) {
            throw new IllegalArgumentException("Codex model '" + requestedModel
                    + "' requires an explicit availableModels grant in model config '"
                    + modelConfigId + "'");
        }
    }

    private boolean isGatedCodexModel(String normalizedModel) {
        return normalizedModel.endsWith(":max")
                || normalizedModel.endsWith(":ultra");
    }

    private boolean isUnsupportedKnownCodexCatalogModel(String model) {
        String normalized = model.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        int separator = normalized.lastIndexOf(':');
        if (separator <= 0 || !"ultra".equals(normalized.substring(separator + 1))) {
            return false;
        }
        String base = normalized.substring(0, separator);
        return "codex-luna".equals(base) || "gpt-5.6-luna".equals(base);
    }

    private Optional<String> normalizeKnownCodexCatalogModel(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        String legacy = CODEX_LEGACY_MODEL_VALUES.get(normalized);
        if (legacy != null) {
            return Optional.of(legacy);
        }

        int separator = normalized.lastIndexOf(':');
        String base = separator > 0 ? normalized.substring(0, separator) : normalized;
        String effort = separator > 0 ? normalized.substring(separator + 1) : "medium";
        String fixedLegacyAlias = CODEX_LEGACY_MODEL_VALUES.get(base);
        if (fixedLegacyAlias != null && !"codex-latest".equals(base)
                && !"codex-terra".equals(base) && !"codex-luna".equals(base)) {
            return Optional.of(fixedLegacyAlias);
        }
        if ("extra-high".equals(effort)) {
            effort = "xhigh";
        }
        if (!CODEX_CATALOG_EFFORTS.contains(effort)) {
            return Optional.empty();
        }

        String family;
        if ("codex-latest".equals(base) || "codex-terra".equals(base) || "codex-luna".equals(base)) {
            family = base;
        } else {
            family = CODEX_REAL_MODEL_FAMILIES.get(base);
        }
        if (family == null || ("codex-luna".equals(family) && "ultra".equals(effort))) {
            return Optional.empty();
        }
        return Optional.of(family + ":" + effort);
    }

    private record ModelResolution(@Nullable String model, String source) {
    }

    private CodexTaskDTO toDTO(CodexTaskEntity entity) {
        return toDTO(entity, resolveProviderType(entity));
    }

    private List<CodexTaskDTO> toDTOs(List<CodexTaskEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<String, String> providerTypes = resolveProviderTypes(entities);
        return entities.stream()
                .map(entity -> toDTO(entity, providerTypes.get(entity.getTaskId())))
                .toList();
    }

    private CodexTaskDTO toDTO(CodexTaskEntity entity, String providerType) {
        return CodexTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .runtimeId(entity.getRuntimeId())
                .runtimeRevision(entity.getRuntimeRevision())
                .runtimeType(entity.getRuntimeType())
                .runtimeInstanceId(entity.getRuntimeInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .runtimeAcceptanceState(entity.getRuntimeAcceptanceState())
                .sessionId(entity.getSessionId())
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .providerType(providerType)
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
                .lastOutputAt(entity.getLastOutputAt())
                .responseTimedOut(TaskResponseTimeoutSupport.isResponseTimedOut(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .silentForSeconds(TaskResponseTimeoutSupport.silentForSeconds(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .responseTimeoutThresholdSeconds(TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS)
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .createdAtEpochMs(entity.getCreatedAtEpochMs())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

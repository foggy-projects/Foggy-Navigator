package com.foggy.navigator.gemini.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.gemini.worker.model.dto.GeminiTaskDTO;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.model.form.CreateGeminiTaskForm;
import com.foggy.navigator.gemini.worker.repository.GeminiCodingAgentRepository;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Gemini 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiTaskService implements TaskQueryProvider {

    public static final String AGENT_ID = "gemini-worker";

    private final GeminiTaskRepository taskRepository;
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
    private GeminiCodingAgentRepository codingAgentRepository;

    @Autowired
    @Lazy
    private GeminiStreamRelay streamRelay;

    @Transactional
    public GeminiTaskDTO createTask(String userId, String tenantId, CreateGeminiTaskForm form) {
        return createAndStartTask(userId, tenantId, form);
    }

    @Override
    @Transactional
    public DispatchTaskDTO createTaskDirect(Map<String, Object> params, String userId, String tenantId) {
        CreateGeminiTaskForm form = new CreateGeminiTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setGeminiSessionId((String) params.get("geminiSessionId"));
        form.setSessionId((String) params.get("sessionId"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        GeminiTaskDTO dto = createTask(userId, tenantId, form);
        return getTaskById(dto.getTaskId()).orElseThrow();
    }

    @Override
    @Transactional
    public DispatchTaskDTO resumeTask(String userId, String tenantId, Map<String, Object> params) {
        CreateGeminiTaskForm form = new CreateGeminiTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setSessionId((String) params.get("sessionId"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }

        String sessionId = form.getSessionId();
        if (sessionId != null && !sessionId.isBlank() && sessionEntityRepository != null) {
            String geminiSessionId = readJsonValue(
                    sessionEntityRepository.findById(sessionId)
                            .map(SessionEntity::getProviderStateJson).orElse(null),
                    "geminiSessionId");
            form.setGeminiSessionId(geminiSessionId);
        }
        if (form.getGeminiSessionId() == null || form.getGeminiSessionId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 geminiSessionId（需从 session 恢复）");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 workerId");
        }
        workerManagementFacade.validateWorkerOwnership(userId, form.getWorkerId());
        if (!taskRepository.existsByGeminiSessionIdAndWorkerIdAndUserId(
                form.getGeminiSessionId(), form.getWorkerId(), userId)) {
            throw new IllegalArgumentException("Gemini 会话不存在或不属于该 Worker: " + form.getGeminiSessionId());
        }
        if (taskRepository.existsByGeminiSessionIdAndWorkerIdAndUserIdAndStatus(
                form.getGeminiSessionId(), form.getWorkerId(), userId, "RUNNING")) {
            throw new IllegalStateException("该会话正在运行任务，请等待完成或终止后再继续");
        }
        GeminiTaskDTO task = createAndStartTask(userId, tenantId, form);
        return getTaskById(task.getTaskId()).orElseThrow();
    }

    private GeminiTaskDTO createAndStartTask(String userId, String tenantId, CreateGeminiTaskForm form) {
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getPrompt() == null || form.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        workerManagementFacade.validateWorkerOwnership(userId, form.getWorkerId());
        String cwd = form.getCwd();
        if ((cwd == null || cwd.isBlank()) && form.getDirectoryId() != null && !form.getDirectoryId().isBlank()) {
            cwd = workerManagementFacade.getDirectoryPath(userId, form.getDirectoryId());
        }
        if (cwd != null) {
            cwd = cwd.replace('\\', '/');
        }

        String effectiveAgentId = resolveLogicalAgentId(form.getAgentId(), form.getSessionId());
        String effectiveModelConfigId = resolveEffectiveModelConfigId(form.getModelConfigId(), effectiveAgentId);
        String effectiveModel = resolveEffectiveModel(form.getModel(), effectiveAgentId, effectiveModelConfigId);
        String sessionId = resolveSessionId(userId, tenantId, form.getPrompt(), form.getSessionId(), effectiveAgentId);

        GeminiTaskEntity entity = new GeminiTaskEntity();
        entity.setTaskId(IdGenerator.shortId());
        entity.setSessionId(sessionId);
        entity.setDirectoryId(form.getDirectoryId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setResolvedAgentId(effectiveAgentId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setModel(effectiveModel);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setGeminiSessionId(form.getGeminiSessionId());

        persistTask(entity);

        GeminiAuthResult auth = resolveGeminiAuth(form.getWorkerId(), effectiveModelConfigId);
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        putIfNotBlank(providerConfig, "geminiSessionId", form.getGeminiSessionId());
        putIfNotBlank(providerConfig, "baseUrl", auth.baseUrl());
        if (auth.envVars() != null && !auth.envVars().isEmpty()) {
            providerConfig.put("extraEnvVars", auth.envVars());
        }

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .model(entity.getModel())
                .maxTurns(form.getMaxTurns())
                .apiKey(auth.apiKey())
                .providerType(AGENT_ID)
                .providerConfig(providerConfig)
                .build());

        log.info("Created Gemini task: taskId={}, workerId={}, sessionId={}",
                entity.getTaskId(), entity.getWorkerId(), entity.getSessionId());
        return toDTO(entity);
    }

    private String resolveSessionId(String userId, String tenantId, String prompt,
                                    @Nullable String existingSessionId, @Nullable String agentId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return createPlatformSession(userId, tenantId, prompt, agentId);
        }

        if (sessionManager == null) {
            log.warn("SessionManager not available, reusing Gemini sessionId without persisting message: {}", existingSessionId);
            return existingSessionId;
        }

        Session existing = sessionManager.getSession(existingSessionId);
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("Session not found or access denied: " + existingSessionId);
        }

        sessionManager.addMessage(existingSessionId, Message.builder()
                .sessionId(existingSessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return existingSessionId;
    }

    private String createPlatformSession(String userId, String tenantId, String prompt, @Nullable String agentId) {
        if (sessionManager == null) {
            log.warn("SessionManager not available, falling back to IdGenerator for Gemini sessionId");
            return IdGenerator.shortId();
        }
        String title = truncate(prompt, 100);
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(agentId != null ? agentId : AGENT_ID)
                .providerType(AGENT_ID)
                .taskName(title)
                .build());
        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return sessionId;
    }

    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                        String prompt, String cwd, String directoryId,
                                        String geminiSessionId) {
        GeminiTaskEntity entity = new GeminiTaskEntity();
        entity.setTaskId(IdGenerator.shortId());
        entity.setSessionId(sessionId);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setPrompt(prompt);
        entity.setCwd(cwd != null ? cwd.replace('\\', '/') : null);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setGeminiSessionId(geminiSessionId);
        persistTask(entity);
        return entity.getTaskId();
    }

    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String geminiSessionId,
                                     String model, Integer ackSeq) {
        GeminiTaskEntity entity = getTaskEntity(taskId);
        applyWorkerMetadata(entity, workerTaskId, geminiSessionId, model);
        if (ackSeq != null) {
            Integer current = entity.getLastAckedSeq();
            entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
        }
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
    }

    @Transactional
    public void completeTask(String taskId, String workerTaskId, String geminiSessionId,
                             String resultText, BigDecimal costUsd, Long inputTokens,
                             Long outputTokens, Long durationMs, Integer numTurns, String model) {
        GeminiTaskEntity entity = getTaskEntity(taskId);
        String previousStatus = entity.getStatus();
        applyWorkerMetadata(entity, workerTaskId, geminiSessionId, model);
        entity.setResultText(resultText);
        entity.setCostUsd(costUsd);
        entity.setInputTokens(inputTokens);
        entity.setOutputTokens(outputTokens);
        entity.setDurationMs(durationMs);
        entity.setNumTurns(numTurns);
        entity.setStatus("COMPLETED");
        persistTask(entity);
        publishStatusChange(entity, previousStatus);
    }

    @Transactional
    public void failTask(String taskId, String workerTaskId, String geminiSessionId, String errorMessage) {
        GeminiTaskEntity entity = getTaskEntity(taskId);
        String previousStatus = entity.getStatus();
        applyWorkerMetadata(entity, workerTaskId, geminiSessionId, null);
        entity.setErrorMessage(errorMessage);
        entity.setStatus("FAILED");
        persistTask(entity);
        publishStatusChange(entity, previousStatus);
    }

    @Transactional
    public void abortTask(String taskId) {
        GeminiTaskEntity entity = getTaskEntity(taskId);
        if ("RUNNING".equals(entity.getStatus()) || "AWAITING_PERMISSION".equals(entity.getStatus())) {
            String previousStatus = entity.getStatus();
            streamRelay.abortRemoteTask(entity);
            streamRelay.abortStream(taskId);
            entity.setStatus("ABORTED");
            persistTask(entity);
            publishStatusChange(entity, previousStatus);
        }
    }

    public void doAbortWorkerTask(String taskId, String remoteTaskId) {
        GeminiTaskEntity entity = getTaskEntity(taskId);
        applyWorkerMetadata(entity, remoteTaskId, null, null);
        persistTask(entity);
        streamRelay.abortRemoteTask(entity);
    }

    public GeminiTaskDTO getTask(String userId, String taskId) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId)
                .map(this::toDTO)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public GeminiTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public boolean hasRunningTask(String geminiSessionId, String workerId, String userId) {
        return taskRepository.existsByGeminiSessionIdAndWorkerIdAndUserIdAndStatus(
                geminiSessionId, workerId, userId, "RUNNING");
    }

    @Override
    public String getProviderType() {
        return AGENT_ID;
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
        return taskRepository.findBySessionId(sessionId).stream().map(this::toDispatchDTO).toList();
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, List.of("RUNNING", "AWAITING_PERMISSION"))
                .stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public void cancelTask(String taskId, String userId) {
        GeminiTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if ("RUNNING".equals(entity.getStatus()) || "AWAITING_PERMISSION".equals(entity.getStatus())) {
            abortTask(taskId);
        }
    }

    private void persistTask(GeminiTaskEntity entity) {
        taskRepository.save(entity);
        syncSessionProjection(entity);
    }

    private void syncSessionProjection(GeminiTaskEntity entity) {
        if (sessionTaskRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        SessionTaskEntity projection = sessionTaskRepository.findByTaskId(entity.getTaskId()).orElseGet(SessionTaskEntity::new);
        fillSessionTaskProjection(projection, entity);
        sessionTaskRepository.save(projection);

        syncSessionEntityProjection(entity);
    }

    private void syncSessionEntityProjection(GeminiTaskEntity entity) {
        if (sessionEntityRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        sessionEntityRepository.findById(entity.getSessionId()).ifPresent(session -> {
            // Skip the save when nothing the projection cares about has changed: this is called per
            // SSE event via recordWorkerProgress, so unguarded saves cause N redundant writes per task.
            boolean changed = false;
            if (!Objects.equals(session.getCurrentWorkerId(), entity.getWorkerId())) {
                session.setCurrentWorkerId(entity.getWorkerId());
                changed = true;
            }
            if (!Objects.equals(session.getCurrentDirectoryId(), entity.getDirectoryId())) {
                session.setCurrentDirectoryId(entity.getDirectoryId());
                changed = true;
            }
            if (!Objects.equals(session.getLatestTaskId(), entity.getTaskId())) {
                session.setLatestTaskId(entity.getTaskId());
                changed = true;
            }
            if (!Objects.equals(session.getLatestModel(), entity.getModel())) {
                session.setLatestModel(entity.getModel());
                changed = true;
            }
            if (!AGENT_ID.equals(session.getProviderType())) {
                session.setProviderType(AGENT_ID);
                changed = true;
            }
            String mergedState = mergeJsonValue(session.getProviderStateJson(), "geminiSessionId", entity.getGeminiSessionId());
            if (!Objects.equals(session.getProviderStateJson(), mergedState)) {
                session.setProviderStateJson(mergedState);
                changed = true;
            }
            if (changed) {
                sessionEntityRepository.save(session);
            }
        });
    }

    private String buildGeminiTaskStateJson(GeminiTaskEntity entity) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, "geminiSessionId", entity.getGeminiSessionId());
        return JsonSupport.write(state);
    }

    private void fillSessionTaskProjection(SessionTaskEntity projection, GeminiTaskEntity entity) {
        projection.setTaskId(entity.getTaskId());
        projection.setSessionId(entity.getSessionId());
        projection.setProviderTaskId(entity.getWorkerTaskId());
        projection.setWorkerId(entity.getWorkerId());
        projection.setUserId(entity.getUserId());
        projection.setTenantId(entity.getTenantId());
        projection.setAgentId(resolveLogicalAgentId(entity));
        projection.setProviderType(AGENT_ID);
        projection.setPrompt(entity.getPrompt());
        projection.setCwd(entity.getCwd());
        projection.setDirectoryId(entity.getDirectoryId());
        projection.setStatus(entity.getStatus());
        projection.setModel(entity.getModel());
        projection.setCostUsd(entity.getCostUsd());
        projection.setInputTokens(entity.getInputTokens());
        projection.setOutputTokens(entity.getOutputTokens());
        projection.setDurationMs(entity.getDurationMs());
        projection.setNumTurns(entity.getNumTurns());
        projection.setResultText(entity.getResultText());
        projection.setErrorMessage(entity.getErrorMessage());
        projection.setLastAckedSeq(entity.getLastAckedSeq());
        projection.setLastAliveAt(entity.getLastAliveAt());
        projection.setSource(entity.getSource());
        projection.setCreatedAt(entity.getCreatedAt());
        projection.setUpdatedAt(entity.getUpdatedAt());
        projection.setModelConfigId(null);
        projection.setTaskStateJson(buildGeminiTaskStateJson(entity));
    }

    private void applyWorkerMetadata(GeminiTaskEntity entity, String workerTaskId, String geminiSessionId, String model) {
        applyIfPresent(workerTaskId, entity::setWorkerTaskId);
        applyIfPresent(geminiSessionId, entity::setGeminiSessionId);
        applyIfPresent(model, entity::setModel);
    }

    private String truncate(@Nullable String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String mergeJsonValue(String json, String key, String value) {
        Map<String, Object> state = JsonSupport.read(json);
        if (value == null || value.isBlank()) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
        return JsonSupport.write(state);
    }

    private void publishStatusChange(GeminiTaskEntity entity, String previousStatus) {
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(AGENT_ID)
                .status(entity.getStatus())
                .previousStatus(previousStatus)
                .errorMessage(entity.getErrorMessage())
                .interactionState(mapInteractionState(entity.getStatus()))
                .build());
    }

    private String mapInteractionState(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED", "ABORTED" -> "ERROR";
            case "AWAITING_PERMISSION" -> "WAITING_CONFIRMATION";
            default -> "PROCESSING";
        };
    }

    private DispatchTaskDTO toDispatchDTO(GeminiTaskEntity entity) {
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(resolveLogicalAgentId(entity))
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
                .geminiSessionId(entity.getGeminiSessionId())
                .build();
    }

    private GeminiTaskDTO toDTO(GeminiTaskEntity entity) {
        return GeminiTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .status(entity.getStatus())
                .geminiSessionId(entity.getGeminiSessionId())
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

    private String resolveLogicalAgentId(@Nullable String explicitAgentId, @Nullable String sessionId) {
        if (explicitAgentId != null && !explicitAgentId.isBlank()) {
            return explicitAgentId;
        }
        if (sessionId == null || sessionId.isBlank() || sessionEntityRepository == null) {
            return null;
        }
        return sessionEntityRepository.findById(sessionId).map(SessionEntity::getAgentId).orElse(null);
    }

    private String resolveLogicalAgentId(GeminiTaskEntity entity) {
        if (entity.getResolvedAgentId() != null && !entity.getResolvedAgentId().isBlank()) {
            return entity.getResolvedAgentId();
        }
        if (entity.getSessionId() == null || entity.getSessionId().isBlank() || sessionEntityRepository == null) {
            return null;
        }
        return sessionEntityRepository.findById(entity.getSessionId()).map(SessionEntity::getAgentId).orElse(null);
    }

    private String resolveEffectiveModelConfigId(String explicitModelConfigId, String agentId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return explicitModelConfigId;
        }
        if (agentId == null || agentId.isBlank() || codingAgentRepository == null) {
            return null;
        }
        return codingAgentRepository.findByAgentId(agentId)
                .map(CodingAgentEntity::getDefaultModelConfigId)
                .orElse(null);
    }

    private String resolveEffectiveModel(String requestedModel, String agentId, String modelConfigId) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        if (modelConfigId != null && !modelConfigId.isBlank() && llmModelManager != null) {
            Optional<LlmModelConfigDTO> config = llmModelManager.getModelConfig(modelConfigId);
            if (config.isPresent() && config.get().getModelName() != null && !config.get().getModelName().isBlank()) {
                return config.get().getModelName();
            }
        }
        if (agentId != null && !agentId.isBlank() && codingAgentRepository != null) {
            Optional<CodingAgentEntity> agent = codingAgentRepository.findByAgentId(agentId);
            if (agent.isPresent()
                    && agent.get().getDefaultModel() != null
                    && !agent.get().getDefaultModel().isBlank()) {
                return agent.get().getDefaultModel();
            }
        }
        return null;
    }

    private GeminiAuthResult resolveGeminiAuth(String workerId, String modelConfigId) {
        GeminiConfig workerConfig = workerManagementFacade.getGeminiConfig(workerId);
        String baseUrl = null;
        String apiKey = workerConfig != null ? blankToNull(workerConfig.getAuthToken()) : null;
        Map<String, String> envVars = new LinkedHashMap<>();

        if (modelConfigId != null && !modelConfigId.isBlank() && llmModelManager != null) {
            Optional<LlmModelConfigDTO> configOpt = llmModelManager.getModelConfig(modelConfigId);
            if (configOpt.isPresent()) {
                LlmModelConfigDTO config = configOpt.get();
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                    baseUrl = config.getBaseUrl();
                }
                String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
                if (decryptedApiKey != null && !decryptedApiKey.isBlank()) {
                    apiKey = decryptedApiKey;
                }
                if (config.getEnvVars() != null && !config.getEnvVars().isEmpty()) {
                    envVars.putAll(config.getEnvVars());
                }
            }
        }
        return new GeminiAuthResult(apiKey, baseUrl, envVars.isEmpty() ? null : envVars);
    }

    private static String readJsonValue(String json, String key) {
        Map<String, Object> data = JsonSupport.read(json);
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static void applyIfPresent(String value, Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record GeminiAuthResult(String apiKey, String baseUrl, Map<String, String> envVars) {}
}

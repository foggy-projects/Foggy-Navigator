package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticInput;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphApprovalEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.langgraph.worker.support.LanggraphSkillNameContract;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * LangGraph task lifecycle management exposed through the lookup and command SPI ports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanggraphTaskService implements TaskLookupProvider, TaskCommandProvider {

    public static final String PROVIDER_TYPE = "langgraph-biz-worker";
    public static final String TASK_DIRECTORY_REQUIRED = "TASK_DIRECTORY_REQUIRED";
    static final String COMPLETION_EVIDENCE_STATE_KEY = "completionEvidence";
    static final String DURABLE_RESULT_SCHEMA = "NAVIGATOR_LANGGRAPH_DURABLE_RESULT_V1";
    private static final String TASK_DIRECTORY_REQUIRED_MESSAGE =
            TASK_DIRECTORY_REQUIRED + ": directoryId is required for Actor-owned BizWorker task";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESPOND_TO_TASK,
            TaskQueryCapability.DELETE_TASK);

    private final LanggraphTaskRepository taskRepository;
    private final LanggraphApprovalRepository approvalRepository;
    private final LanggraphWorkerService workerService;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionTaskRepository sessionTaskRepository;
    private final SessionEntityRepository sessionEntityRepository;
    private final SessionMessageRepository sessionMessageRepository;

    @Autowired(required = false)
    @Nullable
    private ErrorDiagnosticService errorDiagnosticService;

    @Value("${foggy.navigator.langgraph.worker.include-recent-conversation:false}")
    private boolean includeRecentConversation;

    // ── Typed task-provider ports ──────────────────────────────────────────

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
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
                userId, List.of("RUNNING", "PENDING")).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public DispatchTaskDTO createTaskDirect(Map<String, Object> params, String userId, String tenantId) {
        CreateLanggraphTaskForm form = new CreateLanggraphTaskForm();
        form.setAgentId((String) params.get("agentId"));
        form.setSkillName(resolveSkillName(params, "direct task params"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setCwd((String) params.get("cwd"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setMaxTurns(positiveInteger(firstPresent(params, "maxTurns", "max_turns")));
        form.setAllowedTools(stringListParam(firstPresent(
                params,
                "allowedTools",
                "allowed_tools",
                "authorizedTools",
                "authorized_tools",
                "toolAllowlist",
                "tool_allowlist")));
        form.setContextId((String) params.get("contextId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        if (params.get("context") instanceof Map<?, ?> ctx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) ctx;
            form.setContext(contextMap);
        }
        Object rawRuntimeContext = firstPresent(params, "runtimeContext", "runtime_context");
        if (rawRuntimeContext instanceof Map<?, ?> runtimeCtx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeContextMap = (Map<String, Object>) runtimeCtx;
            form.setRuntimeContext(runtimeContextMap);
        }

        LanggraphTaskDTO task = createTask(userId, tenantId, form);
        return getTaskById(task.getTaskId()).orElseThrow();
    }

    /**
     * Resolve a pending approval through the unified task command port.
     * The caller identity comes exclusively from the authenticated routing context.
     */
    @Override
    @Transactional
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("taskId and authenticated userId are required");
        }

        LanggraphTaskEntity task = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        LanggraphApprovalEntity approval = approvalRepository
                .findByTaskIdAndUserIdAndStatus(taskId, userId, "PENDING")
                .orElseThrow(() -> new IllegalStateException(
                        "No pending approval for task: " + taskId));

        String approvalResult = normalizeApprovalResult(responseValue(
                response, "approvalResult", "approval_result", "decision"));
        String comment = responseValue(response, "comment");

        approval.setApprovalResult(approvalResult);
        approval.setComment(comment);
        approval.setReviewedBy(userId);
        approval.setStatus("approved".equals(approvalResult) ? "APPROVED" : "REJECTED");
        approval.setReviewedAt(java.time.LocalDateTime.now());
        approvalRepository.save(approval);

        if (!StringUtils.hasText(task.getContextId())) {
            throw new IllegalStateException(
                    "Task contextId is required for LangGraph worker resume: " + taskId);
        }
        LanggraphWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        var client = workerService.createClient(worker);
        client.resumeTask(taskId, task.getSessionId(), task.getContextId(), approvalResult, comment)
                .doOnSuccess(ignored -> log.info("Worker resume success: taskId={}", taskId))
                .doOnError(error -> log.error("Worker resume failed: taskId={}", taskId, error))
                .subscribe();
    }

    // ── Task lifecycle ────────────────────────────────────────────────────

    @Transactional
    public LanggraphTaskDTO createTask(String userId, String tenantId, CreateLanggraphTaskForm form) {
        requireTaskDirectoryId(form);
        mergeAllowedToolsIntoRuntimeContext(form);
        String workerId = resolveCompatibleWorkerId(tenantId, form.getWorkerId());

        // 1. Create or reuse session
        String sessionId = form.getSessionId();
        String agentId = resolveAgentId(form);
        if (sessionId == null || sessionManager.getSession(sessionId) == null) {
            sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .agentId(agentId)
                    .providerType(PROVIDER_TYPE)
                    .taskName(truncate(form.getPrompt(), 100))
                    .build());
        }

        // 2. Persist task entity
        String taskId = "lgt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> providerContext = buildProviderContext(form, sessionId);
        LanggraphTaskEntity entity = new LanggraphTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setPrompt(form.getPrompt());
        entity.setStatus("PENDING");
        entity.setModel(form.getModel());
        entity.setModelConfigId(form.getModelConfigId());
        entity.setDirectoryId(form.getDirectoryId());
        entity.setCwd(form.getCwd());
        entity.setContextId(form.getContextId());
        entity.setTaskDeadlineAt(runtimeContextText(form.getRuntimeContext(), "taskDeadlineAt", "task_deadline_at"));
        persistTask(entity);
        persistUserPrompt(sessionId, taskId, form.getPrompt(), form.getAttachments());

        // 3. Publish WorkerTaskStartEvent → LanggraphStreamRelay listens
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        if (!providerContext.isEmpty()) {
            providerConfig.put("context", providerContext);
        }
        if (form.getRuntimeContext() != null && !form.getRuntimeContext().isEmpty()) {
            providerConfig.put("runtimeContext", form.getRuntimeContext());
        }
        if (form.getAttachments() != null && !form.getAttachments().isEmpty()) {
            providerConfig.put("attachments", form.getAttachments());
        }
        putIfNotBlank(providerConfig, "modelConfigId", form.getModelConfigId());
        if (form.getMaxTurns() != null && form.getMaxTurns() > 0) {
            providerConfig.put("maxTurns", form.getMaxTurns());
        }
        putIfNotBlank(providerConfig, LanggraphSkillNameContract.CANONICAL_KEY, form.getSkillName());
        putIfNotBlank(providerConfig, LanggraphSkillNameContract.JAVA_ALIAS_KEY, form.getSkillName());

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .workerId(workerId)
                .userId(userId)
                .tenantId(tenantId)
                .prompt(form.getPrompt())
                .cwd(form.getCwd())
                .model(form.getModel())
                .providerType(PROVIDER_TYPE)
                .providerConfig(providerConfig)
                .build());

        log.info("Created langgraph task: taskId={}, sessionId={}, workerId={}",
                taskId, sessionId, workerId);

        return toDTO(entity);
    }

    private void requireTaskDirectoryId(CreateLanggraphTaskForm form) {
        if (form == null || !StringUtils.hasText(form.getDirectoryId())) {
            throw new IllegalArgumentException(TASK_DIRECTORY_REQUIRED_MESSAGE);
        }
        form.setDirectoryId(form.getDirectoryId().trim());
    }

    private String resolveCompatibleWorkerId(String tenantId, String workerId) {
        if (!StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("LangGraph workerId is required");
        }
        LanggraphWorkerEntity worker = workerService.getWorkerEntity(workerId.trim());
        if (StringUtils.hasText(worker.getTenantId()) && !Objects.equals(worker.getTenantId(), tenantId)) {
            throw new SecurityException("LangGraph worker tenant mismatch");
        }
        return worker.getWorkerId();
    }

    private Map<String, Object> buildProviderContext(CreateLanggraphTaskForm form, String sessionId) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (form.getContext() != null) {
            context.putAll(form.getContext());
        }
        putIfNotBlank(context, "contextId", form.getContextId());
        putIfNotBlank(context, "context_id", form.getContextId());
        putIfNotBlank(context, "session_id", sessionId);

        if (includeRecentConversation) {
            List<Map<String, Object>> recentConversation = recentConversation(sessionId);
            if (!recentConversation.isEmpty()) {
                context.put("recentConversation", recentConversation);
            }
        }
        return context;
    }

    private static String resolveSkillName(Map<String, Object> values, String source) {
        return LanggraphSkillNameContract.resolve(values, (key, ignored) ->
                log.warn("Deprecated LangGraph skill alias '{}' received from {}; use 'skill_name'", key, source));
    }

    private List<Map<String, Object>> recentConversation(String sessionId) {
        if (!StringUtils.hasText(sessionId) || sessionMessageRepository == null) {
            return List.of();
        }
        List<SessionMessageEntity> messages = sessionMessageRepository
                .findBySessionIdOrderByCreatedAtDescIdDesc(sessionId, PageRequest.of(0, 12));
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        messages = new ArrayList<>(messages);
        Collections.reverse(messages);
        return messages.stream()
                .map(this::toConversationMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    private Map<String, Object> toConversationMessage(SessionMessageEntity message) {
        if (message == null || !StringUtils.hasText(message.getContent())) {
            return null;
        }
        String role = message.getRole() != null ? message.getRole().toLowerCase() : "";
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }
        if ("assistant".equals(role) && !isConversationalAssistantMessage(message)) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", truncate(message.getContent(), 1200));
        putIfNotBlank(item, "taskId", message.getTaskId());
        return item;
    }

    private boolean isConversationalAssistantMessage(SessionMessageEntity message) {
        String type = messageType(message);
        return type == null || "TEXT_COMPLETE".equals(type) || "TASK_COMPLETED".equals(type);
    }

    private String messageType(SessionMessageEntity message) {
        if (message == null || !StringUtils.hasText(message.getMetadata())) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(message.getMetadata());
            JsonNode type = node.get("type");
            return type != null && type.isTextual() ? type.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void persistUserPrompt(String sessionId, String taskId, String prompt, List<Map<String, Object>> attachments) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(prompt)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "USER");
        metadata.put("taskId", taskId);
        if (attachments != null && !attachments.isEmpty()) {
            metadata.put("attachments", attachments);
        }
        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .taskId(taskId)
                .role(MessageRole.USER)
                .content(prompt)
                .metadata(metadata)
                .build());
    }

    @Transactional
    public void startTask(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("RUNNING");
            persistTask(entity);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTask(String taskId, String resultText, String structuredOutput, Long durationMs) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String previousStatus = entity.getStatus();
            entity.setStatus("COMPLETED");
            entity.setResultText(resultText);
            entity.setStructuredOutput(structuredOutput);
            entity.setDurationMs(durationMs);
            entity.setTaskSubStatus(null);
            entity.setInterruptionReason(null);
            entity.setInterruptionMessage(null);
            entity.setRecoverable(false);
            persistTask(entity, buildCompletionEvidence(resultText, structuredOutput));
            publishStatusChange(entity, previousStatus);
            log.info("Task completed: taskId={}", taskId);
        });
    }

    public int resolveDispatchCount(String taskId) {
        SessionTaskEntity task = sessionTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null || !StringUtils.hasText(task.getProviderTaskId())) {
            return 1;
        }
        Map<String, Object> state = ProviderStateCodec.parseObject(task.getTaskStateJson());
        int attemptNumber = positiveInteger(state.get("attemptNumber")) != null
                ? positiveInteger(state.get("attemptNumber")) : 1;
        int recoveryCount = positiveInteger(state.get("recoveryCount")) != null
                ? positiveInteger(state.get("recoveryCount")) : 0;
        if (recoveryCount == 0 && (StringUtils.hasText(stringValue(state.get("recoveryCorrelationKey")))
                || StringUtils.hasText(stringValue(state.get("recoveryOfTaskId"))))) {
            recoveryCount = 1;
        }
        return 1 + Math.max(0, attemptNumber - 1) + recoveryCount;
    }

    /**
     * Persists the allowlisted diagnostic snapshot before the terminal message
     * is emitted, then returns the same safe envelope with its opaque reference.
     */
    public ErrorEnvelope attachDiagnostic(String taskId,
                                          ErrorEnvelope envelope,
                                          ErrorDiagnosticInput input) {
        if (errorDiagnosticService == null || envelope == null) return envelope;
        LanggraphTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) return envelope;
        envelope.setTaskId(entity.getTaskId());
        envelope.setProviderType(PROVIDER_TYPE);
        envelope.setRuntimeType("LANGGRAPH_BIZ");
        if (input != null) input.setWorkerLabel(entity.getWorkerId());
        String diagnosticRef = errorDiagnosticService.createSnapshotSafely(
                envelope, input, entity.getSessionId(), entity.getUserId(), entity.getTenantId());
        envelope.setDiagnosticRef(diagnosticRef);
        return envelope;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String taskId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            String previousStatus = entity.getStatus();
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            if (!StringUtils.hasText(entity.getTaskSubStatus())) {
                entity.setTaskSubStatus("FAILED");
            }
            entity.setRecoverable(false);
            persistTask(entity);
            publishStatusChange(entity, previousStatus);
            log.warn("Task failed: taskId={}, failureType={}", taskId,
                    StringUtils.hasText(errorMessage) ? "PROVIDER_REPORTED" : "UNSPECIFIED");
        });
    }

    @Override
    @Transactional
    public void cancelTaskDirect(String taskId, String userId) {
        LanggraphTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if ("COMPLETED".equals(entity.getStatus())
                || "FAILED".equals(entity.getStatus())
                || "ABORTED".equals(entity.getStatus())) {
            return;
        }
        throw new UnsupportedOperationException("TERMINATION_REQUEST_NOT_SUPPORTED");
    }

    @Override
    @Transactional
    public void cancelTaskDirect(String taskId, String userId, boolean force) {
        cancelTaskDirect(taskId, userId);
    }

    public void recordTaskInterruption(String taskId, String reason, String errorMessage) {
        recordTaskInterruption(taskId, reason, errorMessage, true);
    }

    public void recordTaskInterruptionProjection(String taskId, String reason, String errorMessage) {
        recordTaskInterruption(taskId, reason, errorMessage, false);
    }

    private void recordTaskInterruption(
            String taskId,
            String reason,
            String errorMessage,
            boolean recordWorkerInterruption
    ) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setTaskSubStatus("INTERRUPTED");
            entity.setInterruptionReason(reason);
            entity.setInterruptionMessage(errorMessage);
            entity.setRecoverable(true);
            persistTask(entity);
            if (recordWorkerInterruption) {
                recordRecoverableInterruption(entity, reason, errorMessage);
            }
        });
    }

    @Override
    @Transactional
    public void deleteTask(String userId, String taskId) {
        LanggraphTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if ("RUNNING".equals(entity.getStatus()) || "PENDING".equals(entity.getStatus())) {
            throw new IllegalStateException("Cannot delete active task: " + taskId);
        }
        taskRepository.delete(entity);
        sessionTaskRepository.deleteByTaskId(taskId);
        log.info("Task deleted: taskId={}", taskId);
    }

    private void persistTask(LanggraphTaskEntity entity) {
        persistTask(entity, null);
    }

    private void persistTask(LanggraphTaskEntity entity, Map<String, Object> completionEvidence) {
        LanggraphTaskEntity saved = taskRepository.save(entity);
        syncSessionTask(saved, completionEvidence);
        syncSessionProjection(saved);
    }

    private void recordRecoverableInterruption(
            LanggraphTaskEntity entity,
            String reason,
            String errorMessage
    ) {
        if (entity == null || !StringUtils.hasText(entity.getWorkerId())) {
            return;
        }
        try {
            LanggraphWorkerEntity worker = workerService.getWorkerEntity(entity.getWorkerId());
            var client = workerService.createClient(worker);
            client.recordInterruption(
                    entity.getTaskId(),
                    entity.getSessionId(),
                    entity.getContextId(),
                    reason,
                    errorMessage,
                    buildInterruptionContext(entity)
            ).doOnSuccess(resp -> log.info(
                    "Worker interruption recorded: taskId={}, reason={}, status={}",
                    entity.getTaskId(), reason, resp != null ? resp.get("status") : "empty"
            )).doOnError(e -> log.warn(
                    "Worker interruption record failed: taskId={}, reason={}, error={}",
                    entity.getTaskId(), reason, e.getMessage()
            )).subscribe();
        } catch (Exception e) {
            log.warn(
                    "Unable to record worker interruption: taskId={}, reason={}, error={}",
                    entity.getTaskId(), reason, e.getMessage()
            );
        }
    }

    private Map<String, Object> buildInterruptionContext(LanggraphTaskEntity entity) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfNotBlank(context, "contextId", entity.getContextId());
        putIfNotBlank(context, "session_id", entity.getSessionId());
        putIfNotBlank(context, "agentId", resolveAgentId(entity));
        putIfNotBlank(context, "taskStatus", entity.getStatus());
        return context;
    }

    private void syncSessionTask(
            LanggraphTaskEntity entity,
            Map<String, Object> completionEvidence) {
        if (entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskId(entity.getTaskId())
                .orElseGet(SessionTaskEntity::new);
        sessionTask.setTaskId(entity.getTaskId());
        sessionTask.setSessionId(entity.getSessionId());
        sessionTask.setProviderType(PROVIDER_TYPE);
        sessionTask.setProviderTaskId(entity.getTaskId());
        sessionTask.setWorkerId(entity.getWorkerId());
        sessionTask.setUserId(entity.getUserId());
        sessionTask.setTenantId(entity.getTenantId());
        sessionTask.setAgentId(resolveAgentId(entity));
        sessionTask.setDirectoryId(entity.getDirectoryId());
        sessionTask.setPrompt(entity.getPrompt());
        sessionTask.setCwd(entity.getCwd());
        sessionTask.setStatus(entity.getStatus());
        sessionTask.setModel(entity.getModel());
        sessionTask.setModelConfigId(entity.getModelConfigId());
        sessionTask.setDurationMs(entity.getDurationMs());
        sessionTask.setResultText(entity.getResultText());
        sessionTask.setErrorMessage(entity.getErrorMessage());
        sessionTask.setSource("PLATFORM");
        sessionTask.setCreatedAt(entity.getCreatedAt());
        sessionTask.setUpdatedAt(entity.getUpdatedAt());
        sessionTask.setTaskStateJson(buildTaskStateJson(
                entity, sessionTask.getTaskStateJson(), completionEvidence));
        sessionTaskRepository.save(sessionTask);
    }

    private void syncSessionProjection(LanggraphTaskEntity entity) {
        if (entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        sessionEntityRepository.findById(entity.getSessionId()).ifPresent(session -> {
            boolean changed = false;
            String agentId = resolveAgentId(entity);
            if (!Objects.equals(session.getAgentId(), agentId)) {
                session.setAgentId(agentId);
                changed = true;
            }
            if (!Objects.equals(session.getProviderType(), PROVIDER_TYPE)) {
                session.setProviderType(PROVIDER_TYPE);
                changed = true;
            }
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
            if (entity.getUpdatedAt() != null && !entity.getUpdatedAt().equals(session.getLastActivityAt())) {
                session.setLastActivityAt(entity.getUpdatedAt());
                changed = true;
            }
            if (changed) {
                sessionEntityRepository.save(session);
            }
        });
    }

    private String buildTaskStateJson(
            LanggraphTaskEntity entity,
            String existingJson,
            Map<String, Object> completionEvidence) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, ProviderStateCodec.FIELD_CONTEXT_ID, entity.getContextId());
        putIfNotBlank(state, ProviderStateCodec.FIELD_STRUCTURED_OUTPUT, entity.getStructuredOutput());
        putIfNotBlank(state, "taskSubStatus", entity.getTaskSubStatus());
        putIfNotBlank(state, "interruptionReason", entity.getInterruptionReason());
        putIfNotBlank(state, "interruptionMessage", entity.getInterruptionMessage());
        putIfNotBlank(state, "taskDeadlineAt", entity.getTaskDeadlineAt());
        if (entity.getRecoverable() != null) {
            state.put("recoverable", entity.getRecoverable());
        }
        if (completionEvidence != null && !completionEvidence.isEmpty()) {
            state.put(COMPLETION_EVIDENCE_STATE_KEY, completionEvidence);
        }
        return ProviderStateCodec.mergeTaskValues(existingJson, PROVIDER_TYPE, state);
    }

    private Map<String, Object> buildCompletionEvidence(
            String resultText,
            String structuredOutput) {
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        boolean finalOutputPresent = resultText != null && !resultText.isEmpty();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", DURABLE_RESULT_SCHEMA);
        evidence.put("finalOutputPresent", finalOutputPresent);
        evidence.put("finalOutputDurable", finalOutputPresent);
        evidence.put("finalOutputDigest", finalOutputPresent ? sha256(resultText) : null);
        evidence.put("finalOutputRecordedAt", finalOutputPresent ? recordedAt : null);
        boolean structuredOutputPresent = StringUtils.hasText(structuredOutput);
        evidence.put("structuredOutputPresent", structuredOutputPresent);
        evidence.put("structuredOutputDigest",
                structuredOutputPresent ? canonicalJsonDigest(structuredOutput) : null);
        evidence.put("resultRecoverable", finalOutputPresent);
        return evidence;
    }

    private String canonicalJsonDigest(String json) {
        try {
            return sha256(OBJECT_MAPPER.writeValueAsString(canonicalJsonValue(OBJECT_MAPPER.readTree(json))));
        } catch (Exception error) {
            log.warn("Structured output digest unavailable: errorType={}",
                    error.getClass().getSimpleName());
            return null;
        }
    }

    private Object canonicalJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), canonicalJsonValue(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(value -> result.add(canonicalJsonValue(value)));
            return result;
        }
        return OBJECT_MAPPER.convertValue(node, Object.class);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String resolveAgentId(CreateLanggraphTaskForm form) {
        return form.getAgentId() == null || form.getAgentId().isBlank()
                ? PROVIDER_TYPE
                : form.getAgentId();
    }

    private String resolveAgentId(LanggraphTaskEntity entity) {
        return entity.getAgentId() == null || entity.getAgentId().isBlank()
                ? PROVIDER_TYPE
                : entity.getAgentId();
    }

    // ── Approval lifecycle (Doc 31 §16.4: Java side manages audit) ─────

    /**
     * Record an approval request received from Worker SSE event.
     */
    @Transactional
    public LanggraphApprovalEntity createApprovalRecord(
            String taskId, String sessionId, String userId,
            String approvalType, String summary, String payload) {
        LanggraphApprovalEntity entity = new LanggraphApprovalEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setApprovalType(approvalType);
        entity.setSummary(summary);
        entity.setPayload(payload);
        entity.setStatus("PENDING");
        approvalRepository.save(entity);
        log.info("Created approval record: taskId={}, type={}", taskId, approvalType);
        return entity;
    }

    public LanggraphTaskDTO getTask(String userId, String taskId) {
        LanggraphTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDTO(entity);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────

    private DispatchTaskDTO toDispatchDTO(LanggraphTaskEntity entity) {
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .agentId(resolveAgentId(entity))
                .userId(entity.getUserId())
                .providerType(PROVIDER_TYPE)
                .prompt(entity.getPrompt())
                .status(entity.getStatus())
                .model(entity.getModel())
                .modelConfigId(entity.getModelConfigId())
                .directoryId(entity.getDirectoryId())
                .cwd(entity.getCwd())
                .contextId(entity.getContextId())
                .resultText(entity.getResultText())
                .structuredOutput(entity.getStructuredOutput())
                .errorMessage(entity.getErrorMessage())
                .durationMs(entity.getDurationMs())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private LanggraphTaskDTO toDTO(LanggraphTaskEntity entity) {
        return LanggraphTaskDTO.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .agentId(resolveAgentId(entity))
                .userId(entity.getUserId())
                .prompt(entity.getPrompt())
                .status(entity.getStatus())
                .model(entity.getModel())
                .modelConfigId(entity.getModelConfigId())
                .directoryId(entity.getDirectoryId())
                .cwd(entity.getCwd())
                .contextId(entity.getContextId())
                .resultText(entity.getResultText())
                .structuredOutput(entity.getStructuredOutput())
                .errorMessage(entity.getErrorMessage())
                .durationMs(entity.getDurationMs())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void publishStatusChange(LanggraphTaskEntity entity, String previousStatus) {
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .tenantId(entity.getTenantId())
                .agentId(resolveAgentId(entity))
                .status(entity.getStatus())
                .previousStatus(previousStatus)
                .errorMessage(entity.getErrorMessage())
                .interactionState(deriveInteractionState(entity.getStatus()))
                .recoverable(entity.getRecoverable())
                .build());
    }

    private String deriveInteractionState(String status) {
        if ("RUNNING".equals(status) || "PENDING".equals(status)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        return (s != null && s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String responseValue(Map<String, Object> response, String... keys) {
        if (response == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = response.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private static String normalizeApprovalResult(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("approvalResult is required");
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "approve", "approved", "allow" -> "approved";
            case "reject", "rejected", "deny" -> "rejected";
            default -> throw new IllegalArgumentException(
                    "approvalResult must be approved or rejected");
        };
    }

    private static String runtimeContextText(Map<String, Object> runtimeContext, String... keys) {
        if (runtimeContext == null || runtimeContext.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = runtimeContext.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private static Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attachmentsParam(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }

    private static List<String> stringListParam(Object value) {
        if (value == null) {
            return null;
        }
        List<String> items = new ArrayList<>();
        if (value instanceof String text) {
            for (String item : text.replace(";", ",").split(",")) {
                if (StringUtils.hasText(item)) {
                    items.add(item.trim());
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text && StringUtils.hasText(text)) {
                    items.add(text.trim());
                }
            }
        }
        return items.isEmpty() ? null : List.copyOf(items);
    }

    private static void mergeAllowedToolsIntoRuntimeContext(CreateLanggraphTaskForm form) {
        if (form == null || form.getAllowedTools() == null || form.getAllowedTools().isEmpty()) {
            return;
        }
        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        if (form.getRuntimeContext() != null) {
            runtimeContext.putAll(form.getRuntimeContext());
        }

        Map<String, Object> executionPolicy = new LinkedHashMap<>();
        copyStringKeyedMap(runtimeContext.get("execution_policy"), executionPolicy);
        copyStringKeyedMap(runtimeContext.get("executionPolicy"), executionPolicy);
        if (!hasAnyKey(
                executionPolicy,
                "allowed_tools",
                "allowedTools",
                "authorized_tools",
                "authorizedTools",
                "tool_allowlist",
                "toolAllowlist")) {
            executionPolicy.put("allowed_tools", form.getAllowedTools());
        }
        runtimeContext.remove("executionPolicy");
        runtimeContext.put("execution_policy", executionPolicy);
        form.setRuntimeContext(runtimeContext);
    }

    private static void copyStringKeyedMap(Object source, Map<String, Object> target) {
        if (!(source instanceof Map<?, ?> sourceMap)) {
            return;
        }
        sourceMap.forEach((key, value) -> {
            if (key instanceof String text) {
                target.put(text, value);
            }
        });
    }

    private static boolean hasAnyKey(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

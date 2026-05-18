package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphApprovalEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.ApproveTaskForm;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
import java.util.UUID;

/**
 * LangGraph task lifecycle management + TaskQueryProvider SPI implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanggraphTaskService implements TaskQueryProvider {

    public static final String PROVIDER_TYPE = "langgraph-biz-worker";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LanggraphTaskRepository taskRepository;
    private final LanggraphApprovalRepository approvalRepository;
    private final LanggraphWorkerService workerService;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionTaskRepository sessionTaskRepository;
    private final SessionEntityRepository sessionEntityRepository;
    private final SessionMessageRepository sessionMessageRepository;

    // ── TaskQueryProvider SPI ──────────────────────────────────────────────

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
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
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setCwd((String) params.get("cwd"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setMaxTurns(positiveInteger(firstPresent(params, "maxTurns", "max_turns")));
        form.setContextId((String) params.get("contextId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        if (params.get("context") instanceof Map<?, ?> ctx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) ctx;
            form.setContext(contextMap);
        }
        if (params.get("runtimeContext") instanceof Map<?, ?> runtimeCtx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeContextMap = (Map<String, Object>) runtimeCtx;
            form.setRuntimeContext(runtimeContextMap);
        }

        LanggraphTaskDTO task = createTask(userId, tenantId, form);
        return getTaskById(task.getTaskId()).orElseThrow();
    }

    @Override
    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        assertWorkerOwnedByUser(workerId, userId);

        Map<String, SessionTaskEntity> latestBySession = new LinkedHashMap<>();
        for (SessionTaskEntity task : sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(workerId, userId)) {
            if (PROVIDER_TYPE.equals(task.getProviderType()) && task.getSessionId() != null) {
                latestBySession.putIfAbsent(task.getSessionId(), task);
            }
        }

        return latestBySession.values().stream()
                .map(this::toWorkerSessionMap)
                .toList();
    }

    @Override
    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        assertSessionOwnedByWorker(workerId, sessionId, userId);

        List<SessionMessageEntity> messages = sessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        long userCount = messages.stream().filter(message -> "user".equalsIgnoreCase(message.getRole())).count();
        long assistantCount = messages.stream().filter(message -> "assistant".equalsIgnoreCase(message.getRole())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user_count", userCount);
        result.put("assistant_count", assistantCount);
        result.put("total", messages.size());
        return result;
    }

    @Override
    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                              String userId, Integer offset, Integer limit) {
        assertSessionOwnedByWorker(workerId, sessionId, userId);

        List<SessionMessageEntity> messages = sessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int fromIndex = Math.max(0, offset == null ? 0 : offset);
        if (fromIndex >= messages.size()) {
            return List.of();
        }
        int requestedLimit = limit == null ? messages.size() - fromIndex : Math.max(0, limit);
        int toIndex = Math.min(messages.size(), fromIndex + requestedLimit);
        if (toIndex <= fromIndex) {
            return List.of();
        }

        return messages.subList(fromIndex, toIndex).stream()
                .map(this::toWorkerSessionMessageMap)
                .toList();
    }

    @Override
    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        assertWorkerOwnedByUser(workerId, userId);

        long total = sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(workerId, userId).stream()
                .filter(task -> PROVIDER_TYPE.equals(task.getProviderType()))
                .map(SessionTaskEntity::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", 0);
        result.put("total", total);
        result.put("source", "session-store");
        return result;
    }

    // ── Task lifecycle ────────────────────────────────────────────────────

    @Transactional
    public LanggraphTaskDTO createTask(String userId, String tenantId, CreateLanggraphTaskForm form) {
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
        entity.setWorkerId(form.getWorkerId());
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
        persistUserPrompt(sessionId, taskId, form.getPrompt());

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

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .workerId(form.getWorkerId())
                .userId(userId)
                .tenantId(tenantId)
                .prompt(form.getPrompt())
                .cwd(form.getCwd())
                .model(form.getModel())
                .providerType(PROVIDER_TYPE)
                .providerConfig(providerConfig)
                .build());

        log.info("Created langgraph task: taskId={}, sessionId={}, workerId={}",
                taskId, sessionId, form.getWorkerId());

        return toDTO(entity);
    }

    private Map<String, Object> buildProviderContext(CreateLanggraphTaskForm form, String sessionId) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (form.getContext() != null) {
            context.putAll(form.getContext());
        }
        putIfNotBlank(context, "contextId", form.getContextId());
        putIfNotBlank(context, "context_id", form.getContextId());
        putIfNotBlank(context, "session_id", sessionId);

        List<Map<String, Object>> recentConversation = recentConversation(sessionId);
        if (!recentConversation.isEmpty()) {
            context.put("recentConversation", recentConversation);
        }
        return context;
    }

    private List<Map<String, Object>> recentConversation(String sessionId) {
        if (!StringUtils.hasText(sessionId) || sessionMessageRepository == null) {
            return List.of();
        }
        List<SessionMessageEntity> messages = sessionMessageRepository
                .findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, 12));
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

    private void persistUserPrompt(String sessionId, String taskId, String prompt) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(prompt)) {
            return;
        }
        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .taskId(taskId)
                .role(MessageRole.USER)
                .content(prompt)
                .metadata(Map.of("type", "USER", "taskId", taskId))
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
            entity.setStatus("COMPLETED");
            entity.setResultText(resultText);
            entity.setStructuredOutput(structuredOutput);
            entity.setDurationMs(durationMs);
            entity.setTaskSubStatus(null);
            entity.setInterruptionReason(null);
            entity.setInterruptionMessage(null);
            entity.setRecoverable(false);
            persistTask(entity);
            log.info("Task completed: taskId={}", taskId);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String taskId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            if (!StringUtils.hasText(entity.getTaskSubStatus())) {
                entity.setTaskSubStatus("FAILED");
            }
            persistTask(entity);
            log.warn("Task failed: taskId={}, error={}", taskId, errorMessage);
        });
    }

    @Override
    @Transactional
    public void cancelTask(String taskId, String userId) {
        LanggraphTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!"RUNNING".equals(entity.getStatus()) && !"PENDING".equals(entity.getStatus())) {
            return;
        }
        entity.setStatus("ABORTED");
        entity.setTaskSubStatus("INTERRUPTED");
        entity.setInterruptionReason("user_cancelled");
        entity.setInterruptionMessage("Cancelled by user");
        entity.setRecoverable(true);
        entity.setErrorMessage("Cancelled by user");
        persistTask(entity);
        recordRecoverableInterruption(entity, "user_cancelled", "Cancelled by user");
        log.info("Task cancelled: taskId={}", taskId);
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
        LanggraphTaskEntity saved = taskRepository.save(entity);
        syncSessionTask(saved);
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

    private void syncSessionTask(LanggraphTaskEntity entity) {
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
        sessionTask.setTaskStateJson(buildTaskStateJson(entity));
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

    private String buildTaskStateJson(LanggraphTaskEntity entity) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, "contextId", entity.getContextId());
        putIfNotBlank(state, "structuredOutput", entity.getStructuredOutput());
        putIfNotBlank(state, "taskSubStatus", entity.getTaskSubStatus());
        putIfNotBlank(state, "interruptionReason", entity.getInterruptionReason());
        putIfNotBlank(state, "interruptionMessage", entity.getInterruptionMessage());
        putIfNotBlank(state, "taskDeadlineAt", entity.getTaskDeadlineAt());
        if (entity.getRecoverable() != null) {
            state.put("recoverable", entity.getRecoverable());
        }
        if (state.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize langgraph task state: taskId={}", entity.getTaskId(), e);
            return null;
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

    /**
     * Approve/reject a pending approval and call Worker resume.
     */
    @Transactional
    public void approveTask(String taskId, ApproveTaskForm form) {
        LanggraphApprovalEntity approval = approvalRepository.findByTaskIdAndStatus(taskId, "PENDING")
                .orElseThrow(() -> new IllegalArgumentException("No pending approval for task: " + taskId));

        approval.setApprovalResult(form.getApprovalResult());
        approval.setComment(form.getComment());
        approval.setReviewedBy(form.getReviewedBy());
        approval.setStatus("approved".equalsIgnoreCase(form.getApprovalResult()) ? "APPROVED" : "REJECTED");
        approval.setReviewedAt(java.time.LocalDateTime.now());
        approvalRepository.save(approval);

        // Call Worker resume API (Doc 31 §16.5: only pass taskId)
        LanggraphTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        LanggraphWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        var client = workerService.createClient(worker);

        client.resumeTask(taskId, form.getApprovalResult(), form.getComment())
                .doOnSuccess(resp -> log.info("Worker resume success: taskId={}", taskId))
                .doOnError(e -> log.error("Worker resume failed: taskId={}", taskId, e))
                .subscribe();
    }

    public LanggraphTaskDTO getTask(String userId, String taskId) {
        LanggraphTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDTO(entity);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────

    private void assertWorkerOwnedByUser(String workerId, String userId) {
        LanggraphWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!Objects.equals(worker.getUserId(), userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
    }

    private void assertSessionOwnedByWorker(String workerId, String sessionId, String userId) {
        assertWorkerOwnedByUser(workerId, userId);
        boolean owned = sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .anyMatch(task -> PROVIDER_TYPE.equals(task.getProviderType())
                        && Objects.equals(workerId, task.getWorkerId())
                        && Objects.equals(userId, task.getUserId()));
        if (!owned) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
    }

    private Map<String, Object> toWorkerSessionMap(SessionTaskEntity task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session_id", task.getSessionId());
        map.put("sessionId", task.getSessionId());
        map.put("worker_id", task.getWorkerId());
        map.put("workerId", task.getWorkerId());
        map.put("project", firstNotBlank(task.getCwd(), task.getDirectoryId(), "LangGraph"));
        map.put("model", firstNotBlank(task.getModel(), "biz-default"));
        map.put("status", task.getStatus());
        map.put("latest_task_id", task.getTaskId());
        map.put("taskId", task.getTaskId());
        map.put("prompt", task.getPrompt());
        map.put("created_at", task.getCreatedAt());
        map.put("updated_at", task.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toWorkerSessionMessageMap(SessionMessageEntity message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", firstNotBlank(message.getRole(), "assistant"));
        map.put("content", firstNotBlank(message.getContent(), ""));
        map.put("timestamp", message.getCreatedAt());
        map.put("taskId", message.getTaskId());
        return map;
    }

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

    private static String truncate(String s, int maxLen) {
        return (s != null && s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attachmentsParam(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
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

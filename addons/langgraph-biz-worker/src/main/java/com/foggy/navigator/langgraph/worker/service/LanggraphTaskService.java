package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    private final LanggraphTaskRepository taskRepository;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;

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
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setCwd((String) params.get("cwd"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setContextId((String) params.get("contextId"));
        form.setSessionId((String) params.get("sessionId"));
        if (params.get("context") instanceof Map<?, ?> ctx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) ctx;
            form.setContext(contextMap);
        }

        LanggraphTaskDTO task = createTask(userId, tenantId, form);
        return getTaskById(task.getTaskId()).orElseThrow();
    }

    // ── Task lifecycle ────────────────────────────────────────────────────

    @Transactional
    public LanggraphTaskDTO createTask(String userId, String tenantId, CreateLanggraphTaskForm form) {
        // 1. Create or reuse session
        String sessionId = form.getSessionId();
        if (sessionId == null || sessionManager.getSession(sessionId) == null) {
            sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .agentId(PROVIDER_TYPE)
                    .providerType(PROVIDER_TYPE)
                    .taskName(truncate(form.getPrompt(), 100))
                    .build());
        }

        // 2. Persist task entity
        String taskId = "lgt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LanggraphTaskEntity entity = new LanggraphTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setPrompt(form.getPrompt());
        entity.setStatus("PENDING");
        entity.setModel(form.getModel());
        entity.setModelConfigId(form.getModelConfigId());
        entity.setDirectoryId(form.getDirectoryId());
        entity.setCwd(form.getCwd());
        entity.setContextId(form.getContextId());
        taskRepository.save(entity);

        // 3. Publish WorkerTaskStartEvent → LanggraphStreamRelay listens
        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .workerId(form.getWorkerId())
                .userId(userId)
                .prompt(form.getPrompt())
                .cwd(form.getCwd())
                .model(form.getModel())
                .providerType(PROVIDER_TYPE)
                .providerConfig(form.getContext() != null
                        ? Map.of("context", form.getContext())
                        : Map.of())
                .build());

        log.info("Created langgraph task: taskId={}, sessionId={}, workerId={}",
                taskId, sessionId, form.getWorkerId());

        return toDTO(entity);
    }

    @Transactional
    public void startTask(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("RUNNING");
            taskRepository.save(entity);
        });
    }

    @Transactional
    public void completeTask(String taskId, String resultText, String structuredOutput, Long durationMs) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("COMPLETED");
            entity.setResultText(resultText);
            entity.setStructuredOutput(structuredOutput);
            entity.setDurationMs(durationMs);
            taskRepository.save(entity);
            log.info("Task completed: taskId={}", taskId);
        });
    }

    @Transactional
    public void failTask(String taskId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            taskRepository.save(entity);
            log.warn("Task failed: taskId={}, error={}", taskId, errorMessage);
        });
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
}

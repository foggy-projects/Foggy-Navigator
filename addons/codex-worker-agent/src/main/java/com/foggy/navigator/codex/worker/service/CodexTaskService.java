package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.model.event.CodexTaskStartEvent;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Codex 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodexTaskService implements TaskQueryProvider {

    private final CodexTaskRepository taskRepository;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    @Nullable
    private SessionManager sessionManager;

    @Autowired(required = false)
    @Nullable
    private LlmModelManager llmModelManager;

    /**
     * 创建并启动 Codex 任务
     */
    @Transactional
    public CodexTaskDTO createTask(String userId, String tenantId, CreateCodexTaskForm form) {
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getPrompt() == null || form.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        // 验证 Worker 存在且属于该用户（通过 ClaudeWorkerFacade SPI）
        claudeWorkerFacade.validateWorkerOwnership(userId, form.getWorkerId());

        String taskId = IdGenerator.shortId();

        // 创建平台会话（之前缺失，导致 Codex 任务没有 SessionEntity）
        String sessionId = createPlatformSession(userId, tenantId, form.getPrompt());

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(form.getDirectoryId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(form.getCwd());
        entity.setModel(form.getModel());
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");

        taskRepository.save(entity);
        log.info("Created Codex task: taskId={}, workerId={}, sessionId={}", taskId, form.getWorkerId(), sessionId);

        // 解析 API Key（如有模型配置）
        String apiKey = resolveApiKey(form.getModelConfigId());

        // 发布事件触发 CodexStreamRelay
        eventPublisher.publishEvent(CodexTaskStartEvent.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .workerId(form.getWorkerId())
                .prompt(form.getPrompt())
                .cwd(form.getCwd())
                .codexThreadId(form.getCodexThreadId())
                .model(form.getModel())
                .maxTurns(form.getMaxTurns())
                .apiKey(apiKey)
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
        String taskId = IdGenerator.shortId();

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setPrompt(prompt);
        entity.setCwd(cwd);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(codexThreadId);

        taskRepository.save(entity);
        log.info("Created tracked sync Codex task: taskId={}, sessionId={}", taskId, sessionId);
        return taskId;
    }

    /**
     * 记录 Worker 侧任务元数据与流消费进度。
     */
    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("recordWorkerProgress: task not found: {}", taskId);
            return;
        }

        if (workerTaskId != null && !workerTaskId.isBlank()) {
            entity.setWorkerTaskId(workerTaskId);
        }
        if (codexThreadId != null && !codexThreadId.isBlank()) {
            entity.setCodexThreadId(codexThreadId);
        }
        if (model != null && !model.isBlank()) {
            entity.setModel(model);
        }
        if (ackSeq != null) {
            Integer current = entity.getLastAckedSeq();
            entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
        }
        entity.setLastAliveAt(LocalDateTime.now());
        taskRepository.save(entity);
    }

    /**
     * 获取任务详情
     */
    public CodexTaskDTO getTask(String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
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
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 列出 Worker 下的任务
     */
    public List<CodexTaskDTO> listTasksByWorker(String userId, String workerId) {
        return taskRepository.findByWorkerIdAndUserId(workerId, userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 中止任务
     */
    @Transactional
    public void abortTask(String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if ("COMPLETED".equals(entity.getStatus()) || "FAILED".equals(entity.getStatus())
                || "ABORTED".equals(entity.getStatus())) {
            log.warn("Task {} is already in terminal state: {}", taskId, entity.getStatus());
            return;
        }

        entity.setStatus("ABORTED");
        taskRepository.save(entity);
        log.info("Aborted Codex task: taskId={}", taskId);
    }

    /**
     * 标记任务完成
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String codexThreadId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("completeTask: task not found: {}", taskId);
            return;
        }

        entity.setStatus("COMPLETED");
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        if (resultText != null) entity.setResultText(resultText);
        if (costUsd != null) entity.setCostUsd(costUsd);
        if (inputTokens != null) entity.setInputTokens(inputTokens);
        if (outputTokens != null) entity.setOutputTokens(outputTokens);
        if (durationMs != null) entity.setDurationMs(durationMs);
        if (numTurns != null) entity.setNumTurns(numTurns);
        if (model != null) entity.setModel(model);
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());

        taskRepository.save(entity);
        log.info("Completed Codex task: taskId={}, cost={}", taskId, costUsd);
    }

    /**
     * 标记任务失败
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String codexThreadId, String errorMessage) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) {
            log.warn("failTask: task not found: {}", taskId);
            return;
        }

        entity.setStatus("FAILED");
        entity.setErrorMessage(errorMessage);
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        entity.setLastAliveAt(LocalDateTime.now());

        taskRepository.save(entity);
        log.info("Failed Codex task: taskId={}, error={}", taskId, errorMessage);
    }

    /**
     * 更新 Codex Thread ID
     */
    @Transactional
    public void updateCodexThreadId(String taskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity != null && codexThreadId != null) {
            entity.setCodexThreadId(codexThreadId);
            taskRepository.save(entity);
        }
    }

    // ── TaskQueryProvider 实现 ──

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
        return taskRepository.findBySessionId(sessionId).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, List.of("RUNNING", "AWAITING_PERMISSION")).stream()
                .map(this::toDispatchDTO)
                .toList();
    }

    private DispatchTaskDTO toDispatchDTO(CodexTaskEntity entity) {
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(AGENT_ID)
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
                // Codex-specific
                .codexThreadId(entity.getCodexThreadId())
                .build();
    }

    private static final String AGENT_ID = "codex-worker";

    /**
     * 创建平台 SessionEntity（补齐 Codex 之前缺失的会话记录）
     */
    private String createPlatformSession(String userId, String tenantId, String prompt) {
        if (sessionManager == null) {
            // SessionManager 未注入时降级为纯 ID（保持向后兼容）
            log.warn("SessionManager not available, falling back to IdGenerator for Codex sessionId");
            return IdGenerator.shortId();
        }
        String title = prompt != null && prompt.length() > 100 ? prompt.substring(0, 100) : prompt;
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(AGENT_ID)
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

    private String resolveApiKey(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return null;
        }
        try {
            return llmModelManager.getDecryptedApiKey(modelConfigId);
        } catch (Exception e) {
            log.warn("Failed to resolve API key from modelConfigId={}: {}", modelConfigId, e.getMessage());
            return null;
        }
    }

    private CodexTaskDTO toDTO(CodexTaskEntity entity) {
        return CodexTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .sessionId(entity.getSessionId())
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
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
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

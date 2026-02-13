package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.event.ClaudeTaskStartEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeTaskService {

    private static final String AGENT_ID = "claude-worker";

    private final ClaudeTaskRepository taskRepository;
    private final ClaudeWorkerService workerService;
    private final SessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;

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

        // 2. 创建 Foggy Session
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(AGENT_ID)
                .taskName(truncate(form.getPrompt(), 100))
                .build());

        // 3. 持久化任务
        String taskId = UUID.randomUUID().toString().substring(0, 12);
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(form.getCwd());
        entity.setStatus("RUNNING");
        taskRepository.save(entity);

        log.info("Task created: taskId={}, sessionId={}, workerId={}, userId={}", taskId, sessionId, form.getWorkerId(), userId);

        // 4. 发布任务启动事件 → WorkerStreamRelay 监听
        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), form.getCwd(), null));

        return toDTO(entity);
    }

    /**
     * 恢复任务（resume Claude Code 会话）
     */
    @Transactional
    public TaskDTO resumeTask(String userId, String tenantId, ResumeTaskForm form) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }

        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(AGENT_ID)
                .taskName("Resume: " + truncate(form.getPrompt(), 80))
                .build());

        String taskId = UUID.randomUUID().toString().substring(0, 12);
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setPrompt(form.getPrompt());
        entity.setCwd(form.getCwd());
        entity.setClaudeSessionId(form.getClaudeSessionId());
        entity.setStatus("RUNNING");
        taskRepository.save(entity);

        log.info("Task resumed: taskId={}, claudeSessionId={}", taskId, form.getClaudeSessionId());

        eventPublisher.publishEvent(new ClaudeTaskStartEvent(
                this, taskId, sessionId, form.getWorkerId(), userId,
                form.getPrompt(), form.getCwd(), form.getClaudeSessionId()));

        return toDTO(entity);
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
     * 列出用户的所有任务
     */
    public List<TaskDTO> listTasks(String userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 分页列出用户的任务
     */
    public Page<TaskDTO> listTasks(String userId, int page, int size) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toDTO);
    }

    /**
     * 更新任务状态为完成
     */
    @Transactional
    public void completeTask(String taskId, String claudeSessionId, BigDecimal costUsd,
                              Long inputTokens, Long outputTokens, Long durationMs) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("COMPLETED");
            entity.setClaudeSessionId(claudeSessionId);
            entity.setCostUsd(costUsd);
            entity.setInputTokens(inputTokens);
            entity.setOutputTokens(outputTokens);
            entity.setDurationMs(durationMs);
            taskRepository.save(entity);
            log.info("Task completed: taskId={}, costUsd={}, durationMs={}", taskId, costUsd, durationMs);
        });
    }

    /**
     * 标记任务失败
     */
    @Transactional
    public void failTask(String taskId, String errorMessage) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            taskRepository.save(entity);
            log.warn("Task failed: taskId={}, error={}", taskId, errorMessage);
        });
    }

    /**
     * 标记任务已中止
     */
    @Transactional
    public void abortTask(String taskId) {
        taskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus("ABORTED");
            taskRepository.save(entity);
            log.info("Task aborted: taskId={}", taskId);
        });
    }

    /**
     * 定时检查超时任务（每5分钟）
     * RUNNING 超过 2 小时的任务标记为 FAILED
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void checkTimeoutTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<ClaudeTaskEntity> timedOut = taskRepository.findByStatusAndCreatedAtBefore("RUNNING", cutoff);
        for (ClaudeTaskEntity entity : timedOut) {
            entity.setStatus("FAILED");
            entity.setErrorMessage("Task timed out (exceeded 2 hours)");
            taskRepository.save(entity);
            log.warn("Task timed out: taskId={}, createdAt={}", entity.getTaskId(), entity.getCreatedAt());
        }
        if (!timedOut.isEmpty()) {
            log.info("Marked {} timed-out tasks as FAILED", timedOut.size());
        }
    }

    /**
     * 获取任务实体（内部使用）
     */
    public ClaudeTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private TaskDTO toDTO(ClaudeTaskEntity entity) {
        return TaskDTO.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .status(entity.getStatus())
                .claudeSessionId(entity.getClaudeSessionId())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

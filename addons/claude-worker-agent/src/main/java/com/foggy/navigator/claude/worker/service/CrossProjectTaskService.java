package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectPhaseDTO;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.CrossProjectPhaseEntity;
import com.foggy.navigator.claude.worker.model.entity.CrossProjectTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateCrossProjectTaskForm;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.repository.CrossProjectPhaseRepository;
import com.foggy.navigator.claude.worker.repository.CrossProjectTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.foggy.navigator.common.util.IdGenerator;

/**
 * 跨项目任务编排
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossProjectTaskService {

    private final CrossProjectTaskRepository taskRepository;
    private final CrossProjectPhaseRepository phaseRepository;
    private final ClaudeTaskService claudeTaskService;
    private final CodingAgentService codingAgentService;
    private final WorkingDirectoryService directoryService;
    private final WorkingDirectoryRepository directoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    // === 创建 ===

    @Transactional
    public CrossProjectTaskDTO createTask(String userId, String tenantId, CreateCrossProjectTaskForm form) {
        if (form.getTitle() == null || form.getTitle().isBlank()) {
            throw new IllegalArgumentException("Task title is required");
        }
        if (form.getPhases() == null || form.getPhases().isEmpty()) {
            throw new IllegalArgumentException("At least one phase is required");
        }

        String contextId = IdGenerator.shortId();

        CrossProjectTaskEntity task = new CrossProjectTaskEntity();
        task.setContextId(contextId);
        task.setUserId(userId);
        task.setTenantId(tenantId);
        task.setTitle(form.getTitle());
        task.setDescription(form.getDescription());
        task.setStatus("DRAFT");
        task.setTotalPhases(form.getPhases().size());
        task.setInitialSessionId(form.getInitialSessionId());
        task.setInitialDirectoryId(form.getInitialDirectoryId());
        taskRepository.save(task);

        for (int i = 0; i < form.getPhases().size(); i++) {
            CreateCrossProjectTaskForm.PhaseForm pf = form.getPhases().get(i);
            CrossProjectPhaseEntity phase = new CrossProjectPhaseEntity();
            phase.setPhaseId(IdGenerator.shortId());
            phase.setContextId(contextId);
            phase.setPhaseIndex(i);
            phase.setPhaseName(pf.getPhaseName());
            phase.setPrompt(pf.getPrompt());
            phase.setStatus("PENDING");

            // 从 Agent 解析 directoryId 和 workerId
            if (pf.getAgentId() != null && !pf.getAgentId().isBlank()) {
                phase.setAgentId(pf.getAgentId());
                CodingAgentEntity agent = codingAgentService.getAgentEntity(pf.getAgentId());
                if (pf.getDirectoryId() != null && !pf.getDirectoryId().isBlank()) {
                    phase.setDirectoryId(pf.getDirectoryId());
                } else {
                    phase.setDirectoryId(agent.getDefaultDirectoryId());
                }
                phase.setWorkerId(agent.getWorkerId());
            } else if (pf.getDirectoryId() != null && !pf.getDirectoryId().isBlank()) {
                // 直接指定目录
                phase.setDirectoryId(pf.getDirectoryId());
                WorkingDirectoryEntity dir = directoryRepository.findByDirectoryIdAndUserId(pf.getDirectoryId(), userId)
                        .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + pf.getDirectoryId()));
                phase.setWorkerId(dir.getWorkerId());
            }

            phase.setWorktreeBranch(pf.getWorktreeBranch());
            phaseRepository.save(phase);
        }

        log.info("Cross-project task created: contextId={}, title={}, phases={}",
                contextId, form.getTitle(), form.getPhases().size());
        return getTask(userId, contextId);
    }

    // === 启动 ===

    @Transactional
    public CrossProjectTaskDTO startTask(String userId, String contextId) {
        CrossProjectTaskEntity task = getTaskEntity(userId, contextId);
        if (!"DRAFT".equals(task.getStatus())) {
            throw new IllegalStateException("Task is not in DRAFT status: " + task.getStatus());
        }

        CrossProjectPhaseEntity phase0 = phaseRepository.findByContextIdAndPhaseIndex(contextId, 0)
                .orElseThrow(() -> new IllegalStateException("Phase 0 not found"));

        startPhase(userId, task.getTenantId(), task, phase0, null);

        task.setStatus("RUNNING");
        task.setCurrentPhaseIndex(0);
        taskRepository.save(task);

        log.info("Cross-project task started: contextId={}", contextId);
        return getTask(userId, contextId);
    }

    // === 推进 ===

    @Transactional
    public CrossProjectTaskDTO advancePhase(String userId, String tenantId,
                                             String contextId, String handoffOverride) {
        CrossProjectTaskEntity task = getTaskEntity(userId, contextId);
        if (!"PAUSED".equals(task.getStatus()) && !"RUNNING".equals(task.getStatus())) {
            throw new IllegalStateException("Task cannot advance in status: " + task.getStatus());
        }

        int currentIdx = task.getCurrentPhaseIndex() != null ? task.getCurrentPhaseIndex() : 0;
        CrossProjectPhaseEntity currentPhase = phaseRepository.findByContextIdAndPhaseIndex(contextId, currentIdx)
                .orElseThrow(() -> new IllegalStateException("Current phase not found"));

        if (!"AWAITING_REVIEW".equals(currentPhase.getStatus()) && !"COMPLETED".equals(currentPhase.getStatus())) {
            throw new IllegalStateException("Current phase is not ready for advance: " + currentPhase.getStatus());
        }

        // 更新 handoff（如果用户提供了 override）
        if (handoffOverride != null && !handoffOverride.isBlank()) {
            currentPhase.setHandoffArtifact(handoffOverride);
        }
        currentPhase.setStatus("COMPLETED");
        currentPhase.setCompletedAt(LocalDateTime.now());
        phaseRepository.save(currentPhase);

        int nextIdx = currentIdx + 1;
        if (nextIdx >= task.getTotalPhases()) {
            // 所有 phase 完成
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now());
            aggregateCost(task);
            taskRepository.save(task);
            log.info("Cross-project task completed: contextId={}", contextId);
            return getTask(userId, contextId);
        }

        // 启动下一个 phase
        CrossProjectPhaseEntity nextPhase = phaseRepository.findByContextIdAndPhaseIndex(contextId, nextIdx)
                .orElseThrow(() -> new IllegalStateException("Next phase not found: index=" + nextIdx));

        startPhase(userId, tenantId, task, nextPhase, currentPhase.getHandoffArtifact());

        task.setStatus("RUNNING");
        task.setCurrentPhaseIndex(nextIdx);
        taskRepository.save(task);

        log.info("Cross-project task advanced to phase {}: contextId={}", nextIdx, contextId);
        return getTask(userId, contextId);
    }

    // === 审查触发 ===

    @Transactional
    public TaskDTO triggerReview(String userId, String tenantId, String contextId) {
        CrossProjectTaskEntity task = getTaskEntity(userId, contextId);

        int currentIdx = task.getCurrentPhaseIndex() != null ? task.getCurrentPhaseIndex() : 0;
        CrossProjectPhaseEntity phase = phaseRepository.findByContextIdAndPhaseIndex(contextId, currentIdx)
                .orElseThrow(() -> new IllegalStateException("Current phase not found"));

        if (!"AWAITING_REVIEW".equals(phase.getStatus())) {
            throw new IllegalStateException("Phase is not awaiting review: " + phase.getStatus());
        }

        // 查询下一 phase 名称
        String nextPhaseName = "(最后阶段)";
        if (currentIdx + 1 < task.getTotalPhases()) {
            CrossProjectPhaseEntity nextPhase = phaseRepository.findByContextIdAndPhaseIndex(contextId, currentIdx + 1)
                    .orElse(null);
            if (nextPhase != null) {
                nextPhaseName = nextPhase.getPhaseName();
            }
        }

        // 查询目录信息
        String directoryInfo = "";
        if (phase.getDirectoryId() != null) {
            WorkingDirectoryEntity dir = directoryRepository.findByDirectoryId(phase.getDirectoryId()).orElse(null);
            if (dir != null) {
                directoryInfo = dir.getProjectName() + " (" + dir.getPath() + ")";
            }
        }

        // 组装 review prompt
        String reviewPrompt = buildReviewPrompt(task, phase, nextPhaseName, directoryInfo);

        // 通过 resume 恢复初始会话
        if (task.getInitialSessionId() == null || task.getInitialSessionId().isBlank()) {
            throw new IllegalStateException("Task has no initial session");
        }

        // 从初始会话对应的 ClaudeTask 获取 claudeSessionId 和 workerId
        // 需要从 initialDirectoryId 解析 workerId
        String workerId = null;
        String directoryId = task.getInitialDirectoryId();
        if (directoryId != null) {
            WorkingDirectoryEntity initDir = directoryRepository.findByDirectoryId(directoryId).orElse(null);
            if (initDir != null) {
                workerId = initDir.getWorkerId();
            }
        }
        if (workerId == null) {
            throw new IllegalStateException("Cannot determine worker for initial session");
        }

        ResumeTaskForm resumeForm = new ResumeTaskForm();
        resumeForm.setWorkerId(workerId);
        resumeForm.setPrompt(reviewPrompt);
        resumeForm.setSessionId(task.getInitialSessionId());
        resumeForm.setDirectoryId(directoryId);

        TaskDTO resumedTask = claudeTaskService.resumeTask(userId, tenantId, resumeForm);
        log.info("Review triggered for cross-project task: contextId={}, phaseIndex={}, resumedTaskId={}",
                contextId, currentIdx, resumedTask.getTaskId());
        return resumedTask;
    }

    // === 更新交接信息 ===

    @Transactional
    public CrossProjectPhaseDTO updateHandoff(String userId, String contextId,
                                               String phaseId, String handoffArtifact) {
        getTaskEntity(userId, contextId);
        CrossProjectPhaseEntity phase = phaseRepository.findByPhaseId(phaseId)
                .orElseThrow(() -> new IllegalArgumentException("Phase not found: " + phaseId));
        if (!phase.getContextId().equals(contextId)) {
            throw new IllegalArgumentException("Phase does not belong to this task");
        }
        phase.setHandoffArtifact(handoffArtifact);
        phaseRepository.save(phase);
        log.info("Handoff updated: contextId={}, phaseId={}", contextId, phaseId);
        return toPhaseDTO(phase);
    }

    // === 取消 ===

    @Transactional
    public CrossProjectTaskDTO cancelTask(String userId, String contextId) {
        CrossProjectTaskEntity task = getTaskEntity(userId, contextId);
        if ("COMPLETED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            throw new IllegalStateException("Task is already " + task.getStatus());
        }

        // 标记所有 PENDING/RUNNING 的 phase 为 SKIPPED
        List<CrossProjectPhaseEntity> phases = phaseRepository.findByContextIdOrderByPhaseIndexAsc(contextId);
        for (CrossProjectPhaseEntity phase : phases) {
            if ("PENDING".equals(phase.getStatus()) || "RUNNING".equals(phase.getStatus())
                    || "AWAITING_REVIEW".equals(phase.getStatus())) {
                phase.setStatus("SKIPPED");
                phaseRepository.save(phase);
            }
        }

        task.setStatus("CANCELLED");
        taskRepository.save(task);
        log.info("Cross-project task cancelled: contextId={}", contextId);
        return getTask(userId, contextId);
    }

    // === 查询 ===

    public CrossProjectTaskDTO getTask(String userId, String contextId) {
        CrossProjectTaskEntity task = getTaskEntity(userId, contextId);
        List<CrossProjectPhaseEntity> phases = phaseRepository.findByContextIdOrderByPhaseIndexAsc(contextId);
        return toTaskDTO(task, phases);
    }

    public Page<CrossProjectTaskDTO> listTasks(String userId, int page, int size) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(task -> {
                    List<CrossProjectPhaseEntity> phases =
                            phaseRepository.findByContextIdOrderByPhaseIndexAsc(task.getContextId());
                    return toTaskDTO(task, phases);
                });
    }

    // === Phase 完成回调 ===

    @EventListener
    public void onTaskCompleted(TaskCompletionEvent event) {
        if (event.getExternalTaskId() == null) return;

        phaseRepository.findByClaudeTaskId(event.getExternalTaskId()).ifPresent(phase -> {
            String contextId = phase.getContextId();
            CrossProjectTaskEntity task = taskRepository.findByContextId(contextId).orElse(null);
            if (task == null) return;

            if ("COMPLETED".equals(event.getStatus())) {
                phase.setStatus("AWAITING_REVIEW");
                // 从 ClaudeTask 取得 claudeSessionId 和统计
                try {
                    var claudeTask = claudeTaskService.getTaskEntity(event.getExternalTaskId());
                    phase.setClaudeSessionId(claudeTask.getClaudeSessionId());
                    phase.setCostUsd(claudeTask.getCostUsd());
                    phase.setDurationMs(claudeTask.getDurationMs());
                } catch (Exception e) {
                    log.warn("Failed to enrich phase from ClaudeTask: {}", e.getMessage());
                }
            } else {
                phase.setStatus("FAILED");
                task.setStatus("FAILED");
                taskRepository.save(task);
            }
            phaseRepository.save(phase);

            if ("AWAITING_REVIEW".equals(phase.getStatus())) {
                task.setStatus("PAUSED");
                taskRepository.save(task);

                // SSE 通知到初始会话
                notifyInitialSession(task, phase);
            }

            log.info("Cross-project phase {} event: contextId={}, phaseIndex={}, status={}",
                    event.getStatus(), contextId, phase.getPhaseIndex(), phase.getStatus());
        });
    }

    // === 内部方法 ===

    private void startPhase(String userId, String tenantId,
                             CrossProjectTaskEntity task,
                             CrossProjectPhaseEntity phase,
                             String handoffFromPrevious) {
        String directoryId = phase.getDirectoryId();
        if (directoryId == null) {
            throw new IllegalStateException("Phase has no target directory: phaseIndex=" + phase.getPhaseIndex());
        }

        // 查询 PROJECT 的 projectTaskPrompt（如果目录属于某个 PROJECT）
        String projectTaskPrompt = "";
        WorkingDirectoryEntity dir = directoryRepository.findByDirectoryId(directoryId).orElse(null);
        if (dir != null && dir.getParentProjectId() != null) {
            WorkingDirectoryEntity project = directoryRepository.findByDirectoryId(dir.getParentProjectId()).orElse(null);
            if (project != null && project.getProjectTaskPrompt() != null) {
                projectTaskPrompt = project.getProjectTaskPrompt();
            }
        }

        // 确定 worktree 分支
        String branch = phase.getWorktreeBranch();
        if (branch == null || branch.isBlank()) {
            // 从 Agent 默认分支获取
            if (phase.getAgentId() != null) {
                try {
                    CodingAgentEntity agent = codingAgentService.getAgentEntity(phase.getAgentId());
                    if (agent.getDefaultBranch() != null) {
                        branch = agent.getDefaultBranch();
                    }
                } catch (Exception ignored) {}
            }
        }
        if (branch == null || branch.isBlank()) {
            branch = "cross-project/" + task.getContextId() + "/phase-" + phase.getPhaseIndex();
        }

        // 创建 worktree
        var worktreeDTO = directoryService.createWorktree(userId, tenantId, directoryId, branch);
        phase.setWorktreeDirectoryId(worktreeDTO.getDirectoryId());
        phase.setWorktreeBranch(worktreeDTO.getGitBranch());

        // 注入交接上下文
        if (handoffFromPrevious != null) {
            phase.setIncomingContext(handoffFromPrevious);
        }

        // 组装 prompt
        String fullPrompt = buildPhasePrompt(phase, handoffFromPrevious, projectTaskPrompt);

        // 创建 ClaudeTask
        CreateTaskForm createForm = new CreateTaskForm();
        createForm.setWorkerId(phase.getWorkerId());
        createForm.setPrompt(fullPrompt);
        createForm.setDirectoryId(worktreeDTO.getDirectoryId());

        TaskDTO claudeTask = claudeTaskService.createTask(userId, tenantId, createForm);

        phase.setClaudeTaskId(claudeTask.getTaskId());
        phase.setPhaseSessionId(claudeTask.getSessionId());
        phase.setStatus("RUNNING");
        phase.setStartedAt(LocalDateTime.now());
        phaseRepository.save(phase);
    }

    private String buildPhasePrompt(CrossProjectPhaseEntity phase,
                                     String handoffFromPrevious,
                                     String projectTaskPrompt) {
        StringBuilder sb = new StringBuilder();

        if (projectTaskPrompt != null && !projectTaskPrompt.isBlank()) {
            sb.append("## 项目上下文\n\n").append(projectTaskPrompt).append("\n\n");
        }

        if (handoffFromPrevious != null && !handoffFromPrevious.isBlank()) {
            sb.append("## 上游交接信息\n\n")
              .append("以下是前一阶段完成后的交接信息，请基于此开展工作：\n\n")
              .append(handoffFromPrevious).append("\n\n");
        }

        sb.append("## 本阶段任务\n\n").append(phase.getPrompt()).append("\n\n");

        sb.append("## 交接要求\n\n")
          .append("完成任务后，请在回复最后用以下格式输出交接信息，方便下游项目接手：\n\n")
          .append("```handoff\n")
          .append("- 本阶段做了什么变更\n")
          .append("- 新增/修改了哪些接口、类型定义\n")
          .append("- 下游项目需要注意的事项\n")
          .append("- 关键代码示例（API 签名、请求/响应格式等）\n")
          .append("```\n");

        return sb.toString();
    }

    private String buildReviewPrompt(CrossProjectTaskEntity task, CrossProjectPhaseEntity phase,
                                      String nextPhaseName, String directoryInfo) {
        return "## 跨项目任务阶段完成通知\n\n"
                + "任务: " + task.getTitle() + "\n"
                + "阶段 " + phase.getPhaseIndex() + ": " + phase.getPhaseName() + " 已完成\n\n"
                + "### 完成摘要\n"
                + "工作目录: " + directoryInfo + "\n"
                + "分支: " + (phase.getWorktreeBranch() != null ? phase.getWorktreeBranch() : "N/A") + "\n\n"
                + "### 请审查并生成交接信息\n"
                + "请审查上述阶段的完成情况，为下一阶段 (" + nextPhaseName + ") 生成交接信息。\n"
                + "交接信息应包含接口变更、类型定义、使用示例等关键信息。\n\n"
                + "请用以下格式输出：\n"
                + "```handoff\n"
                + "[交接信息内容]\n"
                + "```\n";
    }

    private void notifyInitialSession(CrossProjectTaskEntity task, CrossProjectPhaseEntity phase) {
        if (task.getInitialSessionId() == null) return;
        try {
            AgentMessage notification = AgentMessage.of(
                    task.getInitialSessionId(),
                    "claude-worker",
                    MessageType.TASK_COMPLETED,
                    Map.of(
                            "taskType", "CROSS_PROJECT_PHASE",
                            "contextId", task.getContextId(),
                            "phaseIndex", phase.getPhaseIndex(),
                            "phaseName", phase.getPhaseName(),
                            "status", phase.getStatus(),
                            "title", task.getTitle()
                    ));
            // Publish AgentMessage event — SessionEventListener in session-module will handle SSE push
            eventPublisher.publishEvent(notification);
        } catch (Exception e) {
            log.warn("Failed to send SSE notification to initial session: {}", e.getMessage());
        }
    }

    private void aggregateCost(CrossProjectTaskEntity task) {
        List<CrossProjectPhaseEntity> phases =
                phaseRepository.findByContextIdOrderByPhaseIndexAsc(task.getContextId());
        BigDecimal total = phases.stream()
                .map(p -> p.getCostUsd() != null ? p.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        task.setTotalCostUsd(total);
    }

    private CrossProjectTaskEntity getTaskEntity(String userId, String contextId) {
        return taskRepository.findByContextIdAndUserId(contextId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Cross-project task not found: " + contextId));
    }

    private CrossProjectTaskDTO toTaskDTO(CrossProjectTaskEntity entity, List<CrossProjectPhaseEntity> phases) {
        return CrossProjectTaskDTO.builder()
                .contextId(entity.getContextId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .currentPhaseIndex(entity.getCurrentPhaseIndex())
                .totalPhases(entity.getTotalPhases())
                .executionMode(entity.getExecutionMode())
                .totalCostUsd(entity.getTotalCostUsd())
                .initialSessionId(entity.getInitialSessionId())
                .initialDirectoryId(entity.getInitialDirectoryId())
                .phases(phases.stream().map(this::toPhaseDTO).toList())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }

    private CrossProjectPhaseDTO toPhaseDTO(CrossProjectPhaseEntity entity) {
        // Enrich with names
        String directoryName = null;
        String workerName = null;
        String agentName = null;

        if (entity.getDirectoryId() != null) {
            directoryName = directoryRepository.findByDirectoryId(entity.getDirectoryId())
                    .map(WorkingDirectoryEntity::getProjectName).orElse(null);
        }
        if (entity.getAgentId() != null) {
            try {
                agentName = codingAgentService.getAgentEntity(entity.getAgentId()).getName();
            } catch (Exception ignored) {}
        }

        return CrossProjectPhaseDTO.builder()
                .phaseId(entity.getPhaseId())
                .phaseIndex(entity.getPhaseIndex())
                .phaseName(entity.getPhaseName())
                .prompt(entity.getPrompt())
                .agentId(entity.getAgentId())
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .status(entity.getStatus())
                .claudeTaskId(entity.getClaudeTaskId())
                .phaseSessionId(entity.getPhaseSessionId())
                .claudeSessionId(entity.getClaudeSessionId())
                .worktreeDirectoryId(entity.getWorktreeDirectoryId())
                .worktreeBranch(entity.getWorktreeBranch())
                .handoffArtifact(entity.getHandoffArtifact())
                .incomingContext(entity.getIncomingContext())
                .directoryName(directoryName)
                .workerName(workerName)
                .agentName(agentName)
                .costUsd(entity.getCostUsd())
                .durationMs(entity.getDurationMs())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

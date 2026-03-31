package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * InnerA2aAgent 实现 — 接收已解析的 A2aContext，执行实际的 Claude Worker 任务创建。
 *
 * sendTask 通过 taskService.createTask() 走完整的任务生命周期：
 * 创建 session → 创建 task entity → 发布 WorkerTaskStartEvent → StreamRelay 启动 SSE。
 * 调用者通过 getTask 轮询状态获取结果。
 *
 * 上下文解析（contextId/contextAlias → session）由外层 ContextResolvingA2aAgent 负责。
 */
class ClaudeWorkerInnerA2aAgent implements InnerA2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerInnerA2aAgent.class);

    /** 去重时间窗口（秒）：同一用户+Agent+提问内容在此窗口内只创建一个任务 */
    private static final int DEDUP_WINDOW_SECONDS = 60;

    private final CodingAgentEntity entity;
    private final ClaudeTaskService taskService;
    private final String defaultCwd;

    ClaudeWorkerInnerA2aAgent(CodingAgentEntity entity, ClaudeTaskService taskService, String defaultCwd) {
        this.entity = entity;
        this.taskService = taskService;
        this.defaultCwd = defaultCwd;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return AgentCardBuilder.fromEntity(entity,
                "coding", "Execute coding tasks via Claude Code CLI",
                List.of("coding", "claude-worker"));
    }

    @Override
    public A2aTask sendTask(A2aContext context) {
        A2aMessage message = context.getMessage();

        // 1. 提取参数
        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));

        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();
        String requestedCwd = stringMeta(meta, "cwd");
        String requestedDirectoryId = stringMeta(meta, "directoryId");

        // ★ 幂等去重
        String dedupKey = computeDedupKey(entity.getUserId(), entity.getAgentId(), prompt);
        Optional<TaskDTO> duplicate = taskService.findRecentByDedupKey(dedupKey, DEDUP_WINDOW_SECONDS);
        if (duplicate.isPresent()) {
            TaskDTO dto = duplicate.get();
            log.info("A2A dedup hit: returning existing taskId={}, dedupKey={}", dto.getTaskId(), dedupKey);
            return toA2aTask(dto);
        }

        // 2. 通过 taskService.createTask() 走完整路径
        //    → 创建 Session → 创建 Task Entity → 发布 WorkerTaskStartEvent
        //    → WorkerStreamRelay 监听事件，启动 SSE 流消费
        String effectiveCwd = requestedCwd != null ? requestedCwd : defaultCwd;
        String effectiveDirectoryId = requestedDirectoryId != null ? requestedDirectoryId : entity.getDefaultDirectoryId();

        CreateTaskForm form = new CreateTaskForm();
        form.setAgentId(entity.getAgentId());
        form.setWorkerId(entity.getWorkerId());
        form.setPrompt(prompt);
        form.setCwd(effectiveCwd);
        form.setDirectoryId(effectiveDirectoryId);
        form.setModel((String) meta.get("model"));
        form.setModelConfigId((String) meta.get("modelConfigId"));
        form.setPermissionMode((String) meta.get("permissionMode"));
        form.setImages((String) meta.get("images"));
        form.setAgentTeamsJson((String) meta.get("agentTeamsJson"));
        form.setAgentTeamsConfigId((String) meta.get("agentTeamsConfigId"));
        if (meta.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }

        // contextId 由 decorator 解析（已生成或已恢复）
        form.setContextId(context.getContextId());

        // 多轮会话：从已解析上下文获取 claudeSessionId（使 Worker 恢复 CLI session）
        form.setClaudeSessionId(context.getAgentSessionRef());

        // 复用 Navigator 平台 session（由 decorator 从 context store 解析）
        form.setSessionId(context.getNavigatorSessionId());

        TaskDTO task = taskService.createTask(entity.getUserId(), entity.getTenantId(), form);

        // 设置去重键
        taskService.setDedupKey(task.getTaskId(), dedupKey);
        // 注意：contextStore.saveSessionRef 由外层 ContextResolvingA2aAgent 负责

        log.info("A2A sendTask via createTask: agentId={}, taskId={}, sessionId={}",
                entity.getAgentId(), task.getTaskId(), task.getSessionId());

        // 3. 立即返回 SUBMITTED（SSE 流已由 StreamRelay 在后台启动）
        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", task.getSessionId());
        taskMeta.put("workerId", task.getWorkerId());
        taskMeta.put("directoryId", task.getDirectoryId());
        taskMeta.put("claudeSessionId", task.getClaudeSessionId());

        return A2aTask.builder()
                .id(task.getTaskId())
                .contextId(context.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.SUBMITTED)
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
                .metadata(taskMeta)
                .build();
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        try {
            TaskDTO dto = taskService.getTask(entity.getUserId(), taskId);
            return Optional.of(toA2aTask(dto));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cancelTask(String taskId) {
        taskService.abortTask(taskId);
    }

    // ===== 内部工具方法 =====

    /**
     * 计算去重键：SHA-256(userId:agentId:prompt) 前 32 位十六进制
     */
    private String computeDedupKey(String userId, String agentId, String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((userId + ":" + agentId + ":" + prompt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: use hashCode (less collision-resistant but functional)
            return Integer.toHexString(Objects.hash(userId, agentId, prompt));
        }
    }

    /**
     * TaskDTO → A2aTask 转换（增强版：包含 artifacts、错误描述、元信息）
     */
    private A2aTask toA2aTask(TaskDTO dto) {
        A2aTaskState state = switch (dto.getStatus()) {
            case "PENDING" -> A2aTaskState.SUBMITTED;
            case "RUNNING" -> A2aTaskState.WORKING;
            case "COMPLETED" -> A2aTaskState.COMPLETED;
            case "FAILED" -> A2aTaskState.FAILED;
            case "ABORTED" -> A2aTaskState.CANCELED;
            case "AWAITING_PERMISSION" -> A2aTaskState.INPUT_REQUIRED;
            default -> A2aTaskState.WORKING;
        };

        A2aTask.A2aTaskBuilder builder = A2aTask.builder()
                .id(dto.getTaskId())
                .contextId(dto.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(state)
                        .description(state == A2aTaskState.FAILED ? dto.getErrorMessage() : null)
                        .timestamp(Instant.now())
                        .build());

        // COMPLETED 且有结果文本 → 包含 artifacts
        if (state == A2aTaskState.COMPLETED && dto.getResultText() != null) {
            builder.artifacts(List.of(A2aArtifact.builder()
                    .name("response")
                    .parts(List.of(A2aPart.text(dto.getResultText())))
                    .build()));
        }

        // 元信息（durationMs, costUsd）
        Map<String, Object> meta = new HashMap<>();
        if (dto.getDurationMs() != null) meta.put("durationMs", dto.getDurationMs());
        if (dto.getCostUsd() != null) meta.put("costUsd", dto.getCostUsd());
        if (!meta.isEmpty()) builder.metadata(meta);

        return builder.build();
    }

    private String stringMeta(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

}

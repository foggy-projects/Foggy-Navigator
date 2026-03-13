package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * A2aAgent 适配器 — 包装 CodingAgentEntity + ClaudeTaskService + ClaudeWorkerFacade
 *
 * sendTask 采用异步模式：立即返回 SUBMITTED，后台线程执行 Worker 调用，
 * 调用者通过 getTask 轮询状态获取结果。
 */
class ClaudeWorkerA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerA2aAgent.class);
    private static final int CONTEXT_TTL_HOURS = 24;

    private final CodingAgentEntity entity;
    private final ClaudeTaskService taskService;
    private final ClaudeWorkerFacade workerFacade;
    private final String defaultCwd;
    private final AgentContextStore contextStore;
    private final Executor asyncExecutor;

    ClaudeWorkerA2aAgent(CodingAgentEntity entity, ClaudeTaskService taskService,
                         ClaudeWorkerFacade workerFacade, String defaultCwd,
                         AgentContextStore contextStore, Executor asyncExecutor) {
        this.entity = entity;
        this.taskService = taskService;
        this.workerFacade = workerFacade;
        this.defaultCwd = defaultCwd;
        this.contextStore = contextStore;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        String desc = entity.getDescription();
        if (entity.getProjectSummary() != null) {
            desc = (desc != null ? desc + "\n\n" : "") + "## 项目概述\n" + entity.getProjectSummary();
        }
        return A2aAgentCard.builder()
                .id(entity.getAgentId())
                .name(entity.getName())
                .description(desc)
                .url(entity.getEndpointUrl())
                .version("1.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id("coding")
                                .name("Coding")
                                .description("Execute coding tasks via Claude Code CLI")
                                .tags(List.of("coding", "claude-worker"))
                                .build()
                ))
                .build();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        // 1. 提取参数
        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));

        String contextId = message.getContextId();

        // 多轮会话：通过 contextId 恢复已有 claudeSessionId
        String claudeSessionId = null;
        if (contextId != null && contextStore != null) {
            claudeSessionId = contextStore.findSessionRef(
                    contextId, entity.getUserId(), CONTEXT_TTL_HOURS).orElse(null);
            if (claudeSessionId != null) {
                log.debug("Resuming A2A context {} with claudeSessionId {}", contextId, claudeSessionId);
            }
        }

        // maxTurns: 从 message.metadata 读取（共享调用可指定），默认 3
        int maxTurns = 3;
        if (message.getMetadata() != null && message.getMetadata().containsKey("maxTurns")) {
            maxTurns = ((Number) message.getMetadata().get("maxTurns")).intValue();
        }

        // 提取 sessionId（用于关联 Navigator 会话）
        String navigatorSessionId = null;
        if (message.getMetadata() != null && message.getMetadata().containsKey("sessionId")) {
            navigatorSessionId = (String) message.getMetadata().get("sessionId");
        }
        if (navigatorSessionId == null || navigatorSessionId.isBlank()) {
            navigatorSessionId = "a2a-" + IdGenerator.shortId();
        }

        // 2. 创建 tracked 任务记录（RUNNING 状态）
        String taskId = taskService.createTrackedSyncTask(
                entity.getUserId(), entity.getWorkerId(),
                navigatorSessionId, prompt, defaultCwd,
                entity.getDefaultDirectoryId(), claudeSessionId);

        // 3. 异步执行，注册完成回调
        final String finalClaudeSessionId = claudeSessionId;
        final String finalNavigatorSessionId = navigatorSessionId;
        final int finalMaxTurns = maxTurns;

        workerFacade.asyncQuery(
                entity.getUserId(), entity.getWorkerId(), prompt, defaultCwd,
                finalClaudeSessionId, finalMaxTurns, null, entity.getDefaultDirectoryId()
        ).whenComplete((result, ex) ->
                handleAsyncCompletion(taskId, contextId, finalNavigatorSessionId,
                        prompt, finalClaudeSessionId, result, ex));

        log.info("A2A async sendTask submitted: agentId={}, taskId={}, sessionId={}, directoryId={}",
                entity.getAgentId(), taskId, navigatorSessionId, entity.getDefaultDirectoryId());

        // 4. 立即返回 SUBMITTED
        return A2aTask.builder()
                .id(taskId)
                .contextId(contextId)
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.SUBMITTED)
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
                .build();
    }

    /**
     * 后台异步回调 — 处理 Worker 执行结果
     *
     * 错误处理策略：
     * - Worker SSE error 事件（errorSource=WORKER）→ 标记 FAILED
     * - 传输层异常（超时、断连，errorSource=TRANSPORT）→ 保持 RUNNING
     * - CompletableFuture 层异常（线程池拒绝等）→ 保持 RUNNING
     */
    private void handleAsyncCompletion(String taskId, String contextId,
                                        String sessionId, String prompt,
                                        String prevClaudeSessionId,
                                        Map<String, Object> result, Throwable ex) {
        if (ex != null) {
            // CompletableFuture 层异常（极少见，如线程池拒绝）— 不标记失败
            log.warn("A2A async task {} future error (stays RUNNING): {}", taskId, ex.getMessage());
            return;
        }

        String error = (String) result.get("error");
        String errorSource = (String) result.get("errorSource");
        String resultText = (String) result.get("resultText");
        String newClaudeSessionId = (String) result.get("claudeSessionId");

        // 保存 contextId → claudeSessionId 映射（多轮会话）
        if (contextId != null && contextStore != null && newClaudeSessionId != null) {
            contextStore.saveSessionRef(contextId, "claude-worker",
                    newClaudeSessionId, entity.getUserId(), entity.getAgentId());
        }

        if (error != null && "WORKER".equals(errorSource)) {
            // ★ Worker 明确报错 → 标记 FAILED
            taskService.failTask(taskId, newClaudeSessionId, truncate(error, 500));
            log.warn("A2A task {} FAILED (Worker error): {}", taskId, error);
        } else if (error != null) {
            // 传输层异常（TRANSPORT）→ 保持 RUNNING，不标记失败
            log.warn("A2A task {} transport error (stays RUNNING): {}", taskId, error);
        } else {
            // ★ 成功 → 标记 COMPLETED，保存结果
            taskService.completeTask(taskId, newClaudeSessionId,
                    toBigDecimal(result.get("costUsd")), null, null,
                    toLong(result.get("durationMs")), null, (String) result.get("model"));
            if (resultText != null) {
                taskService.saveTaskResult(taskId, resultText);
            }
            log.info("A2A task {} COMPLETED, resultLength={}",
                    taskId, resultText != null ? resultText.length() : 0);
        }

        // 持久化消息到会话历史（非临时 a2a- 会话才持久化）
        if (sessionId != null && !sessionId.startsWith("a2a-")) {
            taskService.persistTrackedSyncMessages(sessionId, prompt,
                    resultText != null ? resultText : (error != null ? "[错误] " + error : ""));
        }
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

    @Override
    public boolean isAvailable() {
        return true;
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

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number num) return BigDecimal.valueOf(num.doubleValue());
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number num) return num.longValue();
        return null;
    }
}

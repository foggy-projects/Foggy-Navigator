package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.RemoteTaskIdResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * InnerA2aAgent 实现 — 接收已解析的 A2aContext，执行实际的 Codex Worker 任务创建。
 * <p>
 * 异步模式：sendTask() 创建 tracked task + 发布事件，立即返回 SUBMITTED。
 * 上下文解析（contextId/contextAlias → session）由外层 ContextResolvingA2aAgent 负责。
 */
class CodexWorkerInnerA2aAgent implements InnerA2aAgent {

    private static final Logger log = LoggerFactory.getLogger(CodexWorkerInnerA2aAgent.class);

    private final CodingAgentEntity entity;
    private final CodexTaskService taskService;
    private final String defaultCwd;

    CodexWorkerInnerA2aAgent(CodingAgentEntity entity, CodexTaskService taskService, String defaultCwd) {
        this.entity = entity;
        this.taskService = taskService;
        this.defaultCwd = defaultCwd;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return AgentCardBuilder.fromEntity(entity,
                "coding", "Execute coding tasks via OpenAI Codex",
                List.of("coding", "codex-worker"));
    }

    @Override
    public A2aTask sendTask(A2aContext context) {
        A2aMessage message = context.getMessage();

        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));

        // 提取 metadata
        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();
        Integer maxTurns = meta.get("maxTurns") instanceof Number n ? n.intValue() : null;
        String model = stringMeta(meta, "model");
        String requestedCwd = stringMeta(meta, "cwd");
        String requestedDirectoryId = stringMeta(meta, "directoryId");
        String modelConfigId = stringMeta(meta, "modelConfigId");
        String images = imagesMeta(meta.get("images"));

        String effectiveCwd = requestedCwd != null ? requestedCwd : defaultCwd;
        String effectiveDirectoryId = requestedDirectoryId != null ? requestedDirectoryId : entity.getDefaultDirectoryId();

        // 通过 CodexTaskService.createTask() 创建任务并发布 WorkerTaskStartEvent
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setAgentId(entity.getAgentId());
        form.setWorkerId(entity.getWorkerId());
        form.setPrompt(prompt);
        form.setCwd(effectiveCwd);
        form.setDirectoryId(effectiveDirectoryId);
        form.setModel(model);
        form.setMaxTurns(maxTurns);
        form.setImages(images);
        form.setAttachments(attachmentsMeta(meta.get("attachments")));
        form.setModelConfigId(modelConfigId);

        // 多轮会话：从已解析上下文获取 codexThreadId（使 Worker 恢复 Codex session）
        form.setCodexThreadId(context.getAgentSessionRef());

        // 复用 Navigator 平台 session（由 decorator 从 context store 解析）
        form.setSessionId(context.getNavigatorSessionId());

        CodexTaskDTO task = taskService.createTask(entity.getUserId(), entity.getTenantId(), form);

        log.info("Codex A2A sendTask: agentId={}, taskId={}, contextId={}",
                entity.getAgentId(), task.getTaskId(), context.getContextId());

        // 返回 SUBMITTED 状态，后台异步执行
        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", task.getSessionId());
        taskMeta.put("workerId", task.getWorkerId());
        taskMeta.put("directoryId", task.getDirectoryId());
        taskMeta.put("codexThreadId", task.getCodexThreadId());

        return A2aTask.builder()
                .id(task.getTaskId())
                .contextId(context.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.SUBMITTED)
                        .description("Task submitted, executing in background")
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
                .metadata(taskMeta)
                .build();
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        try {
            CodexTaskDTO dto = taskService.getTask(entity.getUserId(), taskId);
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
    public RemoteTaskIdResolution resolveRemoteTaskId(String taskId) {
        try {
            var task = taskService.getTaskEntity(taskId);
            // Codex 严格依赖 workerTaskId，不允许 fallback
            return RemoteTaskIdResolution.of(task.getWorkerTaskId(), false);
        } catch (IllegalArgumentException e) {
            return RemoteTaskIdResolution.of(null, false);
        }
    }

    @Override
    public void abortWorkerTask(String taskId, String remoteTaskId) {
        taskService.doAbortWorkerTask(taskId, remoteTaskId);
    }

    // onPostAbort: Codex 无后置钩子，使用 InnerA2aAgent 默认 no-op

    @Override
    public boolean isSessionBusy(String agentSessionRef) {
        if (agentSessionRef == null || agentSessionRef.isBlank()) return false;
        return taskService.hasRunningTask(agentSessionRef, entity.getWorkerId(), entity.getUserId());
    }

    private A2aTask toA2aTask(CodexTaskDTO dto) {
        A2aTaskState state = switch (dto.getStatus()) {
            case "PENDING" -> A2aTaskState.SUBMITTED;
            case "RUNNING" -> A2aTaskState.WORKING;
            case "COMPLETED" -> A2aTaskState.COMPLETED;
            case "FAILED" -> A2aTaskState.FAILED;
            case "ABORTED" -> A2aTaskState.CANCELED;
            default -> A2aTaskState.WORKING;
        };

        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", dto.getSessionId());
        taskMeta.put("workerId", dto.getWorkerId());
        taskMeta.put("workerTaskId", dto.getWorkerTaskId());
        taskMeta.put("codexThreadId", dto.getCodexThreadId());

        A2aTask.A2aTaskBuilder builder = A2aTask.builder()
                .id(dto.getTaskId())
                .status(A2aTaskStatus.builder()
                        .state(state)
                        .description(dto.getStatus())
                        .timestamp(Instant.now())
                        .build())
                .metadata(taskMeta);

        if (dto.getResultText() != null) {
            builder.artifacts(List.of(A2aArtifact.builder()
                    .name("response")
                    .parts(List.of(A2aPart.text(dto.getResultText())))
                    .build()));
        }
        if (state == A2aTaskState.FAILED && dto.getErrorMessage() != null) {
            builder.status(A2aTaskStatus.builder()
                    .state(A2aTaskState.FAILED)
                    .description(dto.getErrorMessage())
                    .timestamp(Instant.now())
                    .build());
        }

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachmentsMeta(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }

    private String imagesMeta(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining(","));
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}

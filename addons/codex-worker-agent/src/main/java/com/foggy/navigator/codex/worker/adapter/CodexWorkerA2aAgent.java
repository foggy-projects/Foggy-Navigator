package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * A2aAgent 适配器 — 异步模式，返回 SUBMITTED 后在后台由 CodexStreamRelay 处理。
 * <p>
 * 与旧版同步模式的区别：
 * <ul>
 *   <li>旧版：sendTask() 阻塞调 syncQuery()，返回 COMPLETED/FAILED</li>
 *   <li>新版：sendTask() 创建 tracked task + 发布事件，立即返回 SUBMITTED</li>
 * </ul>
 */
class CodexWorkerA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(CodexWorkerA2aAgent.class);
    private static final int CONTEXT_TTL_HOURS = 24;

    private final CodingAgentEntity entity;
    private final CodexTaskService taskService;
    private final String defaultCwd;
    private final AgentContextStore contextStore;

    CodexWorkerA2aAgent(CodingAgentEntity entity, CodexTaskService taskService,
                        String defaultCwd, AgentContextStore contextStore) {
        this.entity = entity;
        this.taskService = taskService;
        this.defaultCwd = defaultCwd;
        this.contextStore = contextStore;
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
                                .description("Execute coding tasks via OpenAI Codex")
                                .tags(List.of("coding", "codex-worker"))
                                .build()
                ))
                .build();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));

        String contextId = message.getContextId();

        // 多轮会话：通过 contextId 恢复已有 codexThreadId
        String codexThreadId = null;
        if (contextId != null && contextStore != null) {
            codexThreadId = contextStore.findSessionRef(
                    contextId, entity.getUserId(), CONTEXT_TTL_HOURS).orElse(null);
            if (codexThreadId != null) {
                log.debug("Resuming A2A context {} with codexThreadId {}", contextId, codexThreadId);
            }
        }

        // 提取 metadata
        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();
        Integer maxTurns = meta != null && meta.get("maxTurns") instanceof Number n ? n.intValue() : null;
        String model = stringMeta(meta, "model");
        String requestedCwd = stringMeta(meta, "cwd");
        String requestedDirectoryId = stringMeta(meta, "directoryId");
        String modelConfigId = stringMeta(meta, "modelConfigId");
        String images = imagesMeta(meta.get("images"));

        String effectiveCwd = requestedCwd != null ? requestedCwd : defaultCwd;
        String effectiveDirectoryId = requestedDirectoryId != null ? requestedDirectoryId : entity.getDefaultDirectoryId();

        // 通过 CodexTaskService.createTask() 创建任务并发布 WorkerTaskStartEvent
        // CodexStreamRelay 监听事件后异步执行 SSE 流消费
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setWorkerId(entity.getWorkerId());
        form.setPrompt(prompt);
        form.setCwd(effectiveCwd);
        form.setDirectoryId(effectiveDirectoryId);
        form.setModel(model);
        form.setMaxTurns(maxTurns);
        form.setImages(images);
        form.setCodexThreadId(codexThreadId);
        form.setModelConfigId(modelConfigId);

        CodexTaskDTO task = taskService.createTask(entity.getUserId(), entity.getTenantId(), form);

        // 保存 contextId → codexThreadId 映射（如果后续 relay 拿到新的 threadId 会更新）
        if (contextId != null && contextStore != null && codexThreadId != null) {
            contextStore.saveSessionRef(contextId, "codex-worker",
                    codexThreadId, entity.getUserId(), entity.getAgentId());
        }

        log.info("Codex A2A sendTask: agentId={}, taskId={}, contextId={}",
                entity.getAgentId(), task.getTaskId(), contextId);

        // 返回 SUBMITTED 状态，后台异步执行
        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", task.getSessionId());
        taskMeta.put("workerId", task.getWorkerId());
        taskMeta.put("directoryId", task.getDirectoryId());
        taskMeta.put("codexThreadId", task.getCodexThreadId());

        return A2aTask.builder()
                .id(task.getTaskId())
                .contextId(contextId)
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
    public boolean isAvailable() {
        return true;
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

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", dto.getSessionId());
        meta.put("workerId", dto.getWorkerId());
        meta.put("workerTaskId", dto.getWorkerTaskId());
        meta.put("codexThreadId", dto.getCodexThreadId());

        A2aTask.A2aTaskBuilder builder = A2aTask.builder()
                .id(dto.getTaskId())
                .status(A2aTaskStatus.builder()
                        .state(state)
                        .description(dto.getStatus())
                        .timestamp(Instant.now())
                        .build())
                .metadata(meta);

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

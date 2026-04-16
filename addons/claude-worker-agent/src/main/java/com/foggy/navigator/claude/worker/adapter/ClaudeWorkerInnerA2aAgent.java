package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.RemoteTaskIdResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * InnerA2aAgent implementation for Claude worker task execution.
 */
class ClaudeWorkerInnerA2aAgent implements InnerA2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerInnerA2aAgent.class);
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
        String prompt = extractPrompt(message);

        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();
        String requestedCwd = stringMeta(meta, "cwd");
        String requestedDirectoryId = stringMeta(meta, "directoryId");

        String effectiveCwd = requestedCwd != null ? requestedCwd : defaultCwd;
        String effectiveDirectoryId = requestedDirectoryId != null
                ? requestedDirectoryId
                : entity.getDefaultDirectoryId();

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
        form.setContextId(context.getContextId());
        form.setClaudeSessionId(context.getAgentSessionRef());
        form.setSessionId(context.getNavigatorSessionId());

        TaskDTO task = taskService.createTask(entity.getUserId(), entity.getTenantId(), form);

        log.info("A2A sendTask via createTask: agentId={}, taskId={}, sessionId={}",
                entity.getAgentId(), task.getTaskId(), task.getSessionId());

        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", task.getSessionId());
        taskMeta.put("workerId", task.getWorkerId());
        taskMeta.put("workerTaskId", task.getWorkerTaskId());
        taskMeta.put("directoryId", task.getDirectoryId());
        taskMeta.put("claudeSessionId", task.getClaudeSessionId());
        taskMeta.put("model", task.getModel());
        taskMeta.put("modelConfigId", task.getModelConfigId());

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
    public Optional<A2aTask> findRecentDuplicate(A2aContext context) {
        String prompt = extractPrompt(context.getMessage());
        String dedupKey = computeDedupKey(entity.getUserId(), entity.getAgentId(), prompt);
        Optional<TaskDTO> duplicate = taskService.findRecentByDedupKey(dedupKey, DEDUP_WINDOW_SECONDS);
        if (duplicate.isEmpty()) {
            return Optional.empty();
        }
        TaskDTO dto = duplicate.get();
        log.info("A2A dedup hit: returning existing taskId={}, dedupKey={}", dto.getTaskId(), dedupKey);
        return Optional.of(toA2aTask(dto));
    }

    @Override
    public void rememberDuplicate(A2aContext context, A2aTask task) {
        String prompt = extractPrompt(context.getMessage());
        String dedupKey = computeDedupKey(entity.getUserId(), entity.getAgentId(), prompt);
        taskService.setDedupKey(task.getId(), dedupKey);
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
    public RemoteTaskIdResolution resolveRemoteTaskId(String taskId) {
        try {
            var task = taskService.getTaskEntity(taskId);
            // Claude 允许 fallback 到平台 taskId（Worker 支持 foggy_task_id 别名）
            return RemoteTaskIdResolution.withFallback(
                    task.getWorkerTaskId(),
                    task.getTaskId(),
                    true
            );
        } catch (IllegalArgumentException e) {
            return RemoteTaskIdResolution.withFallback(null, taskId, true);
        }
    }

    @Override
    public void abortWorkerTask(String taskId, String remoteTaskId) {
        taskService.doAbortWorkerTask(taskId, remoteTaskId);
    }

    @Override
    public void onPostAbort(String taskId) {
        taskService.doPostAbort(taskId);
    }

    @Override
    public boolean isSessionBusy(String agentSessionRef) {
        if (agentSessionRef == null || agentSessionRef.isBlank()) {
            return false;
        }
        return taskService.hasRunningTask(agentSessionRef, entity.getWorkerId());
    }

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
            return Integer.toHexString(Objects.hash(userId, agentId, prompt));
        }
    }

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

        if (state == A2aTaskState.COMPLETED && dto.getResultText() != null) {
            builder.artifacts(List.of(A2aArtifact.builder()
                    .name("response")
                    .parts(List.of(A2aPart.text(dto.getResultText())))
                    .build()));
        }

        Map<String, Object> meta = new HashMap<>();
        if (dto.getDurationMs() != null) {
            meta.put("durationMs", dto.getDurationMs());
        }
        if (dto.getCostUsd() != null) {
            meta.put("costUsd", dto.getCostUsd());
        }
        if (!meta.isEmpty()) {
            builder.metadata(meta);
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

    private String extractPrompt(A2aMessage message) {
        if (message == null || message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));
    }
}

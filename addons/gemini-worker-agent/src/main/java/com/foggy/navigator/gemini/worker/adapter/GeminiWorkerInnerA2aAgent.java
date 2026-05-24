package com.foggy.navigator.gemini.worker.adapter;

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
import com.foggy.navigator.gemini.worker.model.dto.GeminiTaskDTO;
import com.foggy.navigator.gemini.worker.model.form.CreateGeminiTaskForm;
import com.foggy.navigator.gemini.worker.service.GeminiTaskService;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.RemoteTaskIdResolution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

class GeminiWorkerInnerA2aAgent implements InnerA2aAgent {

    static final String CARD_CATEGORY = "coding";
    static final String CARD_DESCRIPTION = "Execute coding tasks via Google Gemini CLI";
    static final List<String> CARD_TAGS = List.of("coding", "gemini-worker");

    private final CodingAgentEntity entity;
    private final GeminiTaskService taskService;
    private final String defaultCwd;

    GeminiWorkerInnerA2aAgent(CodingAgentEntity entity, GeminiTaskService taskService, String defaultCwd) {
        this.entity = entity;
        this.taskService = taskService;
        this.defaultCwd = defaultCwd;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return AgentCardBuilder.fromEntity(entity, CARD_CATEGORY, CARD_DESCRIPTION, CARD_TAGS);
    }

    @Override
    public A2aTask sendTask(A2aContext context) {
        A2aMessage message = context.getMessage();
        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .collect(Collectors.joining("\n"));
        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();
        String requestedWorkerId = stringMeta(meta, "workerId");

        CreateGeminiTaskForm form = new CreateGeminiTaskForm();
        form.setAgentId(entity.getAgentId());
        form.setWorkerId(requestedWorkerId != null ? requestedWorkerId : entity.getWorkerId());
        form.setPrompt(prompt);
        form.setCwd(stringMeta(meta, "cwd") != null ? stringMeta(meta, "cwd") : defaultCwd);
        form.setDirectoryId(stringMeta(meta, "directoryId") != null ? stringMeta(meta, "directoryId") : entity.getDefaultDirectoryId());
        form.setModel(stringMeta(meta, "model"));
        form.setModelConfigId(stringMeta(meta, "modelConfigId"));
        form.setAttachments(attachmentsMeta(meta.get("attachments")));
        form.setGeminiSessionId(context.getAgentSessionRef());
        form.setSessionId(context.getNavigatorSessionId());
        if (meta.get("maxTurns") instanceof Number number) {
            form.setMaxTurns(number.intValue());
        }

        GeminiTaskDTO task = taskService.createTask(entity.getUserId(), entity.getTenantId(), form);
        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("sessionId", task.getSessionId());
        taskMeta.put("workerId", task.getWorkerId());
        taskMeta.put("directoryId", task.getDirectoryId());
        taskMeta.put("geminiSessionId", task.getGeminiSessionId());

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
            GeminiTaskDTO dto = taskService.getTask(entity.getUserId(), taskId);
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
            return RemoteTaskIdResolution.of(taskService.getTaskEntity(taskId).getWorkerTaskId(), false);
        } catch (IllegalArgumentException e) {
            return RemoteTaskIdResolution.of(null, false);
        }
    }

    @Override
    public void abortWorkerTask(String taskId, String remoteTaskId) {
        taskService.doAbortWorkerTask(taskId, remoteTaskId);
    }

    @Override
    public boolean isSessionBusy(String agentSessionRef) {
        if (agentSessionRef == null || agentSessionRef.isBlank()) return false;
        return taskService.hasRunningTask(agentSessionRef, entity.getWorkerId(), entity.getUserId());
    }

    private A2aTask toA2aTask(GeminiTaskDTO dto) {
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
        taskMeta.put("geminiSessionId", dto.getGeminiSessionId());
        A2aTask.A2aTaskBuilder builder = A2aTask.builder()
                .id(dto.getTaskId())
                .status(A2aTaskStatus.builder().state(state).description(dto.getStatus()).timestamp(Instant.now()).build())
                .metadata(taskMeta);
        if (dto.getResultText() != null) {
            builder.artifacts(List.of(A2aArtifact.builder()
                    .name("response")
                    .parts(List.of(A2aPart.text(dto.getResultText())))
                    .build()));
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
}

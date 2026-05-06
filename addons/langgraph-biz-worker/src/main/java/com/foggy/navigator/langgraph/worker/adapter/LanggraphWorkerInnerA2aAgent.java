package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.spi.agent.InnerA2aAgent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inner A2A agent implementation for LangGraph Biz Worker.
 * <p>
 * Phase 1: simplified — no dedup, no session-busy check.
 */
class LanggraphWorkerInnerA2aAgent implements InnerA2aAgent {

    private final CodingAgentEntity entity;
    private final LanggraphTaskService taskService;

    LanggraphWorkerInnerA2aAgent(CodingAgentEntity entity, LanggraphTaskService taskService) {
        this.entity = entity;
        this.taskService = taskService;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return A2aAgentCard.builder()
                .id(entity.getAgentId())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    @Override
    public A2aTask sendTask(A2aContext context) {
        A2aMessage message = context.getMessage();
        String prompt = extractPrompt(message);
        Map<String, Object> meta = message.getMetadata() != null ? message.getMetadata() : Map.of();

        CreateLanggraphTaskForm form = new CreateLanggraphTaskForm();
        form.setAgentId(entity.getAgentId());
        form.setWorkerId(entity.getWorkerId());
        form.setPrompt(prompt);
        form.setDirectoryId(entity.getDefaultDirectoryId());
        form.setModel((String) meta.get("model"));
        form.setModelConfigId((String) meta.get("modelConfigId"));
        form.setContextId(context.getContextId());
        form.setSessionId(context.getNavigatorSessionId());
        if (meta.get("context") instanceof Map<?, ?> ctx) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) ctx;
            form.setContext(contextMap);
        }

        LanggraphTaskDTO task = taskService.createTask(
                entity.getUserId(), entity.getTenantId(), form);

        return A2aTask.builder()
                .id(task.getTaskId())
                .contextId(context.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.SUBMITTED)
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
                .metadata(Map.of(
                        "sessionId", task.getSessionId(),
                        "workerId", task.getWorkerId()))
                .build();
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        try {
            LanggraphTaskDTO dto = taskService.getTask(entity.getUserId(), taskId);
            return Optional.of(toA2aTask(dto));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cancelTask(String taskId) {
        taskService.failTask(taskId, "Cancelled by user");
    }

    private A2aTask toA2aTask(LanggraphTaskDTO dto) {
        A2aTaskState state = switch (dto.getStatus()) {
            case "PENDING" -> A2aTaskState.SUBMITTED;
            case "RUNNING" -> A2aTaskState.WORKING;
            case "COMPLETED" -> A2aTaskState.COMPLETED;
            case "FAILED" -> A2aTaskState.FAILED;
            case "ABORTED" -> A2aTaskState.CANCELED;
            default -> A2aTaskState.WORKING;
        };
        A2aTask.A2aTaskBuilder builder = A2aTask.builder()
                .id(dto.getTaskId())
                .status(A2aTaskStatus.builder()
                        .state(state)
                        .timestamp(Instant.now())
                        .build());
                        
        if (dto.getResultText() != null && !dto.getResultText().isBlank()) {
            builder.artifacts(java.util.List.of(
                A2aArtifact.builder()
                    .parts(java.util.List.of(A2aPart.text(dto.getResultText())))
                    .build()
            ));
        }
        
        return builder.build();
    }

    private String extractPrompt(A2aMessage message) {
        if (message.getParts() != null) {
            for (A2aPart part : message.getParts()) {
                if (part.getText() != null) return part.getText();
            }
        }
        return "";
    }
}

package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A2aAgent 适配器 — 包装 CodingAgentEntity + ClaudeTaskService
 */
class ClaudeWorkerA2aAgent implements A2aAgent {

    private final CodingAgentEntity entity;
    private final ClaudeTaskService taskService;

    ClaudeWorkerA2aAgent(CodingAgentEntity entity, ClaudeTaskService taskService) {
        this.entity = entity;
        this.taskService = taskService;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return A2aAgentCard.builder()
                .name(entity.getName())
                .description(entity.getDescription())
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
        // Extract prompt text from A2aMessage parts
        String prompt = message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2aPart::getText)
                .findFirst()
                .orElse("");

        // Note: actual task creation requires CreateTaskForm with workerId, etc.
        // This is a simplified adapter — full implementation would populate form from entity
        return A2aTask.builder()
                .id("pending")
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.SUBMITTED)
                        .description("Task submitted to " + entity.getName())
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
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

    @Override
    public boolean isAvailable() {
        return true;
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

        return A2aTask.builder()
                .id(dto.getTaskId())
                .status(A2aTaskStatus.builder()
                        .state(state)
                        .description(dto.getStatus())
                        .timestamp(Instant.now())
                        .build())
                .build();
    }
}

package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A2aAgent 适配器 — 包装 CodingAgentEntity + ClaudeTaskService + ClaudeWorkerFacade
 */
class ClaudeWorkerA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerA2aAgent.class);
    private static final int CONTEXT_TTL_HOURS = 24;

    private final CodingAgentEntity entity;
    private final ClaudeTaskService taskService;
    private final ClaudeWorkerFacade workerFacade;
    private final String defaultCwd;
    private final AgentContextStore contextStore;

    ClaudeWorkerA2aAgent(CodingAgentEntity entity, ClaudeTaskService taskService,
                         ClaudeWorkerFacade workerFacade, String defaultCwd,
                         AgentContextStore contextStore) {
        this.entity = entity;
        this.taskService = taskService;
        this.workerFacade = workerFacade;
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

        Map<String, Object> result = workerFacade.syncQuery(
                entity.getUserId(), entity.getWorkerId(), prompt, defaultCwd,
                claudeSessionId, 1, null);

        String resultText = (String) result.get("resultText");
        String error = (String) result.get("error");
        String newClaudeSessionId = (String) result.get("claudeSessionId");

        // 保存 contextId → claudeSessionId 映射
        if (contextId != null && contextStore != null && newClaudeSessionId != null) {
            contextStore.saveSessionRef(contextId, "claude-worker",
                    newClaudeSessionId, entity.getUserId(), entity.getAgentId());
        }

        if (error != null) {
            return A2aTask.builder()
                    .id(UUID.randomUUID().toString())
                    .contextId(contextId)
                    .status(A2aTaskStatus.builder()
                            .state(A2aTaskState.FAILED)
                            .description(error)
                            .timestamp(Instant.now())
                            .build())
                    .history(List.of(message))
                    .build();
        }

        return A2aTask.builder()
                .id(UUID.randomUUID().toString())
                .contextId(contextId)
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.COMPLETED)
                        .timestamp(Instant.now())
                        .build())
                .history(List.of(message))
                .artifacts(List.of(A2aArtifact.builder()
                        .name("response")
                        .parts(List.of(A2aPart.text(resultText != null ? resultText : "")))
                        .build()))
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

package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.common.entity.AgentTaskEntity;
import com.foggy.navigator.session.repository.AgentTaskRepository;
import com.foggy.navigator.spi.task.AgentTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 跨 Agent 任务管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService implements AgentTaskManager {

    private final AgentTaskRepository agentTaskRepository;

    @Override
    @Transactional
    public String createTask(String parentSessionId, String userId, String sourceAgentId,
                              String targetAgentId, String taskType, String prompt,
                              String targetSessionId, String externalTaskId) {
        String taskId = UUID.randomUUID().toString().substring(0, 12);
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId(taskId);
        entity.setParentSessionId(parentSessionId);
        entity.setUserId(userId);
        entity.setSourceAgentId(sourceAgentId);
        entity.setTargetAgentId(targetAgentId);
        entity.setTargetSessionId(targetSessionId);
        entity.setTaskType(taskType);
        entity.setStatus("PENDING");
        entity.setPrompt(prompt);
        entity.setExternalTaskId(externalTaskId);
        agentTaskRepository.save(entity);

        log.info("AgentTask created: taskId={}, type={}, source={}, target={}, externalTaskId={}",
                taskId, taskType, sourceAgentId, targetAgentId, externalTaskId);
        return taskId;
    }

    @Override
    public List<Map<String, Object>> listTasksBySession(String sessionId) {
        return agentTaskRepository.findByParentSessionId(sessionId).stream()
                .map(this::toMap)
                .toList();
    }

    @Override
    @Transactional
    public void completeTask(String taskId, String status, String resultSummary) {
        agentTaskRepository.findByTaskId(taskId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setResultSummary(resultSummary);
            entity.setCompletedAt(LocalDateTime.now());
            agentTaskRepository.save(entity);
            log.info("AgentTask completed: taskId={}, status={}", taskId, status);
        });
    }

    @Override
    @Transactional
    public void completeByExternalTaskId(String externalTaskId, String taskType, String status, String resultSummary) {
        agentTaskRepository.findByExternalTaskIdAndTaskType(externalTaskId, taskType).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setResultSummary(resultSummary);
            entity.setCompletedAt(LocalDateTime.now());
            agentTaskRepository.save(entity);
            log.info("AgentTask completed via external ID: taskId={}, externalId={}, status={}",
                    entity.getTaskId(), externalTaskId, status);
        });
    }

    @EventListener
    @Transactional
    public void onTaskCompletion(TaskCompletionEvent event) {
        if (event.getExternalTaskId() != null) {
            String taskType = event.getTargetAgentId() != null
                    ? event.getTargetAgentId().toUpperCase().replace("-", "_")
                    : "DELEGATION";
            completeByExternalTaskId(event.getExternalTaskId(), taskType,
                    event.getStatus(), event.getResultSummary());
        } else if (event.getTaskId() != null) {
            completeTask(event.getTaskId(), event.getStatus(), event.getResultSummary());
        }
    }

    private Map<String, Object> toMap(AgentTaskEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", entity.getTaskId());
        map.put("parentSessionId", entity.getParentSessionId());
        map.put("sourceAgentId", entity.getSourceAgentId());
        map.put("targetAgentId", entity.getTargetAgentId());
        map.put("taskType", entity.getTaskType());
        map.put("status", entity.getStatus());
        map.put("prompt", entity.getPrompt());
        map.put("resultSummary", entity.getResultSummary());
        map.put("externalTaskId", entity.getExternalTaskId());
        map.put("createdAt", entity.getCreatedAt());
        map.put("completedAt", entity.getCompletedAt());
        return map;
    }
}

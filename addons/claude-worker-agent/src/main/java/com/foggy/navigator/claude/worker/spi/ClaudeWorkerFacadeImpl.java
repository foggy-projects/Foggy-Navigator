package com.foggy.navigator.claude.worker.spi;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ClaudeWorkerFacade SPI 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeWorkerFacadeImpl implements ClaudeWorkerFacade {

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final WorkerStreamRelay streamRelay;

    @Override
    public List<Map<String, Object>> listWorkers(String userId) {
        return workerService.listWorkers(userId).stream()
                .map(this::workerToMap)
                .toList();
    }

    @Override
    public Map<String, Object> getWorker(String userId, String workerId) {
        return workerToMap(workerService.getWorker(userId, workerId));
    }

    @Override
    public Map<String, Object> createTask(String userId, Map<String, Object> params) {
        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        TaskDTO dto = taskService.createTask(userId, (String) params.get("tenantId"), form);
        return taskToMap(dto);
    }

    @Override
    public Map<String, Object> getTaskStatus(String userId, String taskId) {
        return taskToMap(taskService.getTask(userId, taskId));
    }

    @Override
    public Map<String, Object> abortTask(String userId, String taskId) {
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        streamRelay.abortStream(taskId);
        taskService.abortTask(taskId);
        return Map.of("taskId", taskId, "status", "ABORTED");
    }

    @Override
    public Map<String, Object> resumeSession(String userId, Map<String, Object> params) {
        ResumeTaskForm form = new ResumeTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setClaudeSessionId((String) params.get("claudeSessionId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        TaskDTO dto = taskService.resumeTask(userId, (String) params.get("tenantId"), form);
        return taskToMap(dto);
    }

    @Override
    public List<Map<String, Object>> listWorkerSessions(String userId, String workerId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            return sessions != null ? sessions : List.of();
        } catch (Exception e) {
            log.warn("Failed to list sessions: workerId={}, error={}", workerId, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> workerToMap(WorkerDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workerId", dto.getWorkerId());
        map.put("name", dto.getName());
        map.put("baseUrl", dto.getBaseUrl());
        map.put("authMode", dto.getAuthMode());
        map.put("status", dto.getStatus());
        map.put("hostname", dto.getHostname());
        map.put("workerVersion", dto.getWorkerVersion());
        map.put("lastHeartbeat", dto.getLastHeartbeat());
        map.put("createdAt", dto.getCreatedAt());
        return map;
    }

    private Map<String, Object> taskToMap(TaskDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", dto.getTaskId());
        map.put("sessionId", dto.getSessionId());
        map.put("workerId", dto.getWorkerId());
        map.put("prompt", dto.getPrompt());
        map.put("cwd", dto.getCwd());
        map.put("status", dto.getStatus());
        map.put("claudeSessionId", dto.getClaudeSessionId());
        map.put("costUsd", dto.getCostUsd());
        map.put("durationMs", dto.getDurationMs());
        map.put("errorMessage", dto.getErrorMessage());
        map.put("createdAt", dto.getCreatedAt());
        return map;
    }
}

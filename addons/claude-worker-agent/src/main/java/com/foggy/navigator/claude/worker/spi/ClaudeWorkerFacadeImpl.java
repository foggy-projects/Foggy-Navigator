package com.foggy.navigator.claude.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.event.WorkerEvent;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.model.form.ResumeTaskForm;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    private final WorkingDirectoryRepository directoryRepository;
    private final ObjectMapper objectMapper;

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

    @Override
    public List<Map<String, Object>> listTasks(String userId) {
        return taskService.listTasks(userId).stream()
                .map(this::taskToMap)
                .toList();
    }

    @Override
    public Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                          String cwd, String claudeSessionId, int maxTurns,
                                          String model) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);

            // 根据 maxTurns 调整超时：每轮约 30s
            long timeoutSeconds = Math.max(60, maxTurns * 30L);

            // Collect all SSE events synchronously with bypassPermissions
            List<WorkerEvent> events = client.streamQuery(
                            prompt, cwd, claudeSessionId, model, maxTurns,
                            null, null, null, null, null, "bypassPermissions", null,
                            null, null)
                    .mapNotNull(sse -> {
                        String data = sse.data();
                        if (data == null || data.isEmpty()) return null;
                        try {
                            return objectMapper.readValue(data, WorkerEvent.class);
                        } catch (Exception e) {
                            log.debug("Failed to parse sync query event: {}", e.getMessage());
                            return null;
                        }
                    })
                    .collectList()
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (events == null) events = List.of();

            // Extract result from events
            String resultText = null;
            String newSessionId = claudeSessionId;
            for (WorkerEvent event : events) {
                if (event.getSessionId() != null) {
                    newSessionId = event.getSessionId();
                }
                if ("result".equals(event.getType())) {
                    resultText = event.getContent() != null ? event.getContent() : event.getResult();
                    result.put("costUsd", event.getCostUsd());
                    result.put("durationMs", event.getDurationMs());
                } else if ("error".equals(event.getType())) {
                    result.put("error", event.getError());
                }
            }

            result.put("resultText", resultText);
            result.put("claudeSessionId", newSessionId);

        } catch (Exception e) {
            log.error("syncQuery failed: workerId={}, error={}", workerId, e.getMessage(), e);
            result.put("error", e.getMessage());
            result.put("durationMs", System.currentTimeMillis() - startTime);
        }

        return result;
    }

    @Override
    public Map<String, Object> syncQueryTracked(String userId, String workerId, String prompt,
                                                 String cwd, String claudeSessionId, int maxTurns,
                                                 String model, String sessionId, String directoryId) {
        // 1. 创建 claude_tasks 记录（RUNNING）
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, sessionId, prompt, cwd, directoryId, claudeSessionId);

        // 2. 执行 syncQuery
        Map<String, Object> result = syncQuery(userId, workerId, prompt, cwd, claudeSessionId, maxTurns, model);

        // 3. 完成/失败记录
        String error = (String) result.get("error");
        String newClaudeSessionId = (String) result.get("claudeSessionId");
        if (error != null) {
            taskService.failTask(taskId, newClaudeSessionId, truncate(error, 500));
        } else {
            taskService.completeTask(taskId, newClaudeSessionId,
                    toBigDecimal(result.get("costUsd")),
                    null, null,
                    toLong(result.get("durationMs")),
                    null, (String) result.get("model"));
        }

        result.put("taskId", taskId);
        return result;
    }

    @Override
    public String initDirectory(String userId, String workerId, String path, Map<String, String> files) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.initDirectory(path, files).block(Duration.ofSeconds(30));
            log.info("Initialized directory on worker {}: path={}", workerId, path);
        } catch (Exception e) {
            log.error("Failed to init directory on worker {}: {}", workerId, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize directory: " + e.getMessage(), e);
        }

        // 注册工作目录（如果已存在则返回现有 directoryId）
        Optional<WorkingDirectoryEntity> existing = directoryRepository
                .findByWorkerIdAndPathAndUserId(workerId, path, userId);
        if (existing.isPresent()) {
            return existing.get().getDirectoryId();
        }

        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(IdGenerator.shortId());
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setProjectName("foggy-assistant");
        entity.setPath(path);
        entity.setDirectoryType("STANDARD");
        entity.setWorktree(false);
        directoryRepository.save(entity);
        log.info("Registered working directory: directoryId={}, path={}", entity.getDirectoryId(), path);

        return entity.getDirectoryId();
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

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private java.math.BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof java.math.BigDecimal bd) return bd;
        if (value instanceof Number num) return java.math.BigDecimal.valueOf(num.doubleValue());
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number num) return num.longValue();
        return null;
    }
}

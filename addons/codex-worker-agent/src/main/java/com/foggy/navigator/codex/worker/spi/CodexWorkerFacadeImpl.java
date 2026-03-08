package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.event.WorkerEvent;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.codex.CodexWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * CodexWorkerFacade SPI 实现
 * <p>
 * Codex 配置（baseUrl/authToken/model）从 ClaudeWorkerEntity.codexConfig 获取，
 * 通过 ClaudeWorkerFacade.getCodexConfig(workerId) 解密后使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodexWorkerFacadeImpl implements CodexWorkerFacade {

    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskService taskService;
    private final CodexStreamRelay streamRelay;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> createTask(String userId, Map<String, Object> params) {
        var form = new com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        CodexTaskDTO dto = taskService.createTask(userId, (String) params.get("tenantId"), form);
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
    public Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                          String cwd, String codexThreadId, int maxTurns,
                                          String model) {
        claudeWorkerFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = getRequiredCodexConfig(workerId);
        return doSyncQuery(workerId, codexConfig, prompt, cwd, codexThreadId, model, maxTurns, null);
    }

    @Override
    public Map<String, Object> syncQueryTracked(String userId, String workerId, String prompt,
                                                 String cwd, String codexThreadId, int maxTurns,
                                                 String model, String sessionId) {
        claudeWorkerFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = getRequiredCodexConfig(workerId);

        // 创建 codex_tasks 记录
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, sessionId, prompt, cwd, null, codexThreadId);

        Map<String, Object> result;
        try {
            result = doSyncQuery(workerId, codexConfig, prompt, cwd, codexThreadId, model, maxTurns, null);

            String error = (String) result.get("error");
            String newCodexThreadId = (String) result.get("codexThreadId");
            if (error != null) {
                taskService.failTask(taskId, newCodexThreadId, truncate(error, 500));
            } else {
                taskService.completeTask(taskId, newCodexThreadId,
                        toBigDecimal(result.get("costUsd")),
                        null, null,
                        toLong(result.get("durationMs")),
                        null, (String) result.get("model"));
            }
        } catch (Exception e) {
            log.error("syncQueryTracked failed: taskId={}, error={}", taskId, e.getMessage(), e);
            taskService.failTask(taskId, codexThreadId, truncate(e.getMessage(), 500));
            result = new LinkedHashMap<>();
            result.put("error", e.getMessage());
        }

        result.put("taskId", taskId);
        return result;
    }

    /**
     * 内部 syncQuery 实现
     */
    private Map<String, Object> doSyncQuery(String workerId, CodexConfig codexConfig,
                                             String prompt, String cwd,
                                             String codexThreadId, String model, int maxTurns,
                                             String apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            CodexWorkerClient client = clientFactory.getOrCreate(
                    workerId + ":codex", codexConfig.getBaseUrl(), codexConfig.getAuthToken());
            long timeoutSeconds = Math.max(60, maxTurns * 30L);

            // 优先使用入参 model，其次使用 codexConfig 中配置的默认 model
            String effectiveModel = model != null ? model : codexConfig.getModel();

            List<WorkerEvent> events = client.streamQuery(
                            prompt, cwd, codexThreadId, effectiveModel, maxTurns, apiKey)
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

            String resultText = null;
            StringBuilder assistantTextBuilder = new StringBuilder();
            String newSessionId = codexThreadId;

            for (WorkerEvent event : events) {
                if (event.getSessionId() != null) {
                    newSessionId = event.getSessionId();
                }
                if ("result".equals(event.getType())) {
                    resultText = event.getContent() != null ? event.getContent() : event.getResult();
                    result.put("costUsd", event.getCostUsd());
                    result.put("durationMs", event.getDurationMs());
                    result.put("model", event.getModel());
                } else if ("assistant_text".equals(event.getType())) {
                    if (event.getContent() != null) {
                        assistantTextBuilder.append(event.getContent());
                    }
                } else if ("error".equals(event.getType())) {
                    result.put("error", event.getError());
                }
            }

            if (resultText == null && !assistantTextBuilder.isEmpty()) {
                resultText = assistantTextBuilder.toString();
            }

            result.put("resultText", resultText);
            result.put("codexThreadId", newSessionId);

            log.info("doSyncQuery completed: workerId={}, events={}, hasResult={}, hasError={}",
                    workerId, events.size(), resultText != null, result.containsKey("error"));

        } catch (Exception e) {
            log.error("syncQuery failed: workerId={}, error={}", workerId, e.getMessage(), e);
            result.put("error", e.getMessage());
            result.put("durationMs", System.currentTimeMillis() - startTime);
        }

        return result;
    }

    /**
     * 获取 Codex 配置（必须存在）
     */
    CodexConfig getRequiredCodexConfig(String workerId) {
        CodexConfig config = claudeWorkerFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Codex not configured for worker: " + workerId);
        }
        return config;
    }

    private Map<String, Object> taskToMap(CodexTaskDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", dto.getTaskId());
        map.put("sessionId", dto.getSessionId());
        map.put("workerId", dto.getWorkerId());
        map.put("prompt", dto.getPrompt());
        map.put("cwd", dto.getCwd());
        map.put("status", dto.getStatus());
        map.put("codexThreadId", dto.getCodexThreadId());
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

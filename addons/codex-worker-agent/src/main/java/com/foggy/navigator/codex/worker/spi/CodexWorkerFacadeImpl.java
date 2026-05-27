package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.codex.CodexWorkerFacade;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * CodexWorkerFacade SPI 实现
 * <p>
 * Codex 配置（baseUrl/authToken/model）从 ClaudeWorkerEntity.codexConfig 获取，
 * 通过 WorkerManagementFacade.getCodexConfig(workerId) 解密后使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodexWorkerFacadeImpl implements CodexWorkerFacade {

    private final WorkerManagementFacade workerManagementFacade;
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
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
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
        taskService.abortTask(taskId);
        return Map.of("taskId", taskId, "status", "ABORTED");
    }

    @Override
    public Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                          String cwd, String codexThreadId, int maxTurns,
                                          String model) {
        workerManagementFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = getRequiredCodexConfig(workerId);
        return doSyncQuery(workerId, codexConfig, prompt, cwd, codexThreadId, model, maxTurns, null);
    }

    @Override
    public Map<String, Object> syncQueryTracked(String userId, String workerId, String prompt,
                                                 String cwd, String codexThreadId, int maxTurns,
                                                 String model, String sessionId) {
        workerManagementFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = getRequiredCodexConfig(workerId);

        // 创建 codex_tasks 记录
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, sessionId, prompt, cwd, null, codexThreadId);

        Map<String, Object> result;
        try {
            result = doSyncQuery(workerId, codexConfig, prompt, cwd, codexThreadId, model, maxTurns, null);

            String error = (String) result.get("error");
            String workerTaskId = (String) result.get("workerTaskId");
            String newCodexThreadId = (String) result.get("codexThreadId");
            if (error != null) {
                taskService.failTask(taskId, workerTaskId, newCodexThreadId, truncate(error, 500));
            } else {
                taskService.completeTask(taskId, workerTaskId, newCodexThreadId,
                        (String) result.get("resultText"),
                        toBigDecimal(result.get("costUsd")),
                        toLong(result.get("inputTokens")),
                        toLong(result.get("outputTokens")),
                        toLong(result.get("durationMs")),
                        toInteger(result.get("numTurns")),
                        (String) result.get("model"));
            }
        } catch (Exception e) {
            log.error("syncQueryTracked failed: taskId={}, error={}", taskId, e.getMessage(), e);
            taskService.failTask(taskId, null, codexThreadId, truncate(e.getMessage(), 500));
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

            SyncQueryAccumulator state = client.streamQuery(
                            prompt, cwd, codexThreadId, effectiveModel, maxTurns, null, null, apiKey, null, null)
                    .reduce(new SyncQueryAccumulator(codexThreadId), (acc, sse) -> {
                        consumeSyncEvent(acc, sse);
                        return acc;
                    })
                    .block(Duration.ofSeconds(timeoutSeconds));
            if (state == null) {
                state = new SyncQueryAccumulator(codexThreadId);
            }

            result.put("workerTaskId", state.workerTaskId);
            result.put("resultText", state.getResultText());
            result.put("codexThreadId", state.codexThreadId);
            result.put("costUsd", state.costUsd);
            result.put("durationMs", state.durationMs);
            result.put("inputTokens", state.inputTokens);
            result.put("outputTokens", state.outputTokens);
            result.put("numTurns", state.numTurns);
            result.put("model", state.model);
            if (state.error != null) {
                result.put("error", state.error);
            }

            log.info("doSyncQuery completed: workerId={}, events={}, hasResult={}, hasError={}",
                    workerId, state.eventCount, state.getResultText() != null, result.containsKey("error"));

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
        CodexConfig config = workerManagementFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Codex not configured for worker: " + workerId);
        }
        return config;
    }

    private Map<String, Object> taskToMap(CodexTaskDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", dto.getTaskId());
        map.put("workerTaskId", dto.getWorkerTaskId());
        map.put("sessionId", dto.getSessionId());
        map.put("workerId", dto.getWorkerId());
        map.put("prompt", dto.getPrompt());
        map.put("cwd", dto.getCwd());
        map.put("status", dto.getStatus());
        map.put("codexThreadId", dto.getCodexThreadId());
        map.put("model", dto.getModel());
        map.put("costUsd", dto.getCostUsd());
        map.put("inputTokens", dto.getInputTokens());
        map.put("outputTokens", dto.getOutputTokens());
        map.put("durationMs", dto.getDurationMs());
        map.put("numTurns", dto.getNumTurns());
        map.put("resultText", dto.getResultText());
        map.put("errorMessage", dto.getErrorMessage());
        map.put("lastAckedSeq", dto.getLastAckedSeq());
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

    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number num) return num.intValue();
        return null;
    }

    private void consumeSyncEvent(SyncQueryAccumulator acc, org.springframework.http.codec.ServerSentEvent<String> sse) {
        String data = sse.data();
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            WorkerEvent event = objectMapper.readValue(data, WorkerEvent.class);
            acc.eventCount++;
            if (event.getTaskId() != null && !event.getTaskId().isBlank()) {
                acc.workerTaskId = event.getTaskId();
            }
            if (event.getSessionId() != null && !event.getSessionId().isBlank()) {
                acc.codexThreadId = event.getSessionId();
            }
            if (event.getModel() != null && !event.getModel().isBlank()) {
                acc.model = event.getModel();
            }
            if ("result".equals(event.getType())) {
                acc.resultText = event.getContent() != null ? event.getContent() : event.getResult();
                acc.costUsd = event.getCostUsd();
                acc.durationMs = event.getDurationMs();
                acc.inputTokens = event.getInputTokens();
                acc.outputTokens = event.getOutputTokens();
                acc.numTurns = event.getNumTurns();
            } else if ("assistant_text".equals(event.getType())) {
                if (event.getContent() != null) {
                    acc.assistantText.append(event.getContent());
                }
            } else if ("error".equals(event.getType())) {
                acc.error = event.getError();
            }
        } catch (Exception e) {
            log.debug("Failed to parse sync query event: {}", e.getMessage());
        }
    }

    private static final class SyncQueryAccumulator {
        private String workerTaskId;
        private String codexThreadId;
        private String model;
        private java.math.BigDecimal costUsd;
        private Long durationMs;
        private Long inputTokens;
        private Long outputTokens;
        private Integer numTurns;
        private String resultText;
        private String error;
        private int eventCount;
        private final StringBuilder assistantText = new StringBuilder();

        private SyncQueryAccumulator(String initialCodexThreadId) {
            this.codexThreadId = initialCodexThreadId;
        }

        private String getResultText() {
            if (resultText != null) {
                return resultText;
            }
            return assistantText.isEmpty() ? null : assistantText.toString();
        }
    }
}

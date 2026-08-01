package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.command.CodexTaskCreateCommand;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.codex.CodexWorkerFacade;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import com.foggy.navigator.spi.task.RuntimeTaskCompletionReadinessProvider;
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
public class CodexWorkerFacadeImpl implements CodexWorkerFacade, RuntimeTaskClosureProvider,
        RuntimeTaskCompletionReadinessProvider {

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskService taskService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> createTask(String userId, Map<String, Object> params) {
        var form = new CodexTaskCreateCommand();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        form.setSessionId((String) params.get("sessionId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        DispatchTaskDTO dto = taskService.createTask(userId, (String) params.get("tenantId"), form);
        return taskToMap(dto);
    }

    @Override
    public Map<String, Object> getTaskStatus(String userId, String taskId) {
        return taskToMap(taskService.getTaskForProvider(
                userId, taskId, CodexTaskService.CODEX_PROVIDER_TYPE));
    }

    @Override
    public Map<String, Object> abortTask(String userId, String taskId) {
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!CodexTaskService.CODEX_PROVIDER_TYPE.equals(task.getProviderType())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        taskService.abortTask(taskId);
        var reconciledTask = taskService.getTaskEntity(taskId);
        return Map.of("taskId", taskId, "status", reconciledTask.getStatus());
    }

    @Override
    public boolean supports(String providerType) {
        return CodexTaskService.CODEX_PROVIDER_TYPE.equals(providerType)
                || CodexTaskService.CODEX_BIZ_PROVIDER_TYPE.equals(providerType)
                || CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public boolean supportsCompletionReadiness(String providerType) {
        return CodexTaskService.CODEX_PROVIDER_TYPE.equals(providerType)
                || CodexTaskService.CODEX_BIZ_PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public Observation inspectCompletionReadiness(
            String taskId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount) {
        return taskService.inspectRuntimeCompletionReadiness(
                taskId, expectedPhysicalWorkerId, expectedDispatchCount);
    }

    @Override
    public TerminationReadiness inspect(String taskId, String expectedPhysicalWorkerId) {
        return taskService.inspectRuntimeTermination(taskId, expectedPhysicalWorkerId);
    }

    @Override
    public TerminationResult terminate(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            String reason,
            String clientRequestId,
            boolean dryRun) {
        return taskService.terminateRuntimeTask(
                taskId, ownerUserId, tenantId, expectedPhysicalWorkerId,
                reason, clientRequestId, dryRun);
    }

    @Override
    public TerminationAdmission prepareTerminationAdmission(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            String reason,
            String clientRequestId) {
        return taskService.prepareRuntimeTerminationAdmission(
                taskId, ownerUserId, tenantId,
                expectedPhysicalWorkerId, reason, clientRequestId);
    }

    @Override
    public ReconciliationResult reconcile(
            String taskId,
            String ownerUserId,
            String tenantId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount,
            String clientRequestId,
            boolean dryRun) {
        return taskService.reconcileRuntimeTask(
                taskId, ownerUserId, tenantId, expectedPhysicalWorkerId,
                clientRequestId, dryRun);
    }

    @Override
    public Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                          String cwd, String codexThreadId, int maxTurns,
                                          String model) {
        workerManagementFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = workerManagementFacade.getCodexConfig(workerId);
        String effectiveModel = model != null ? model : codexConfig != null ? codexConfig.getModel() : null;
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, null, prompt, cwd, null, codexThreadId, effectiveModel);
        return executeTrackedSyncTask(taskId, workerId, codexConfig, prompt, cwd,
                codexThreadId, effectiveModel, maxTurns, null);
    }

    @Override
    public Map<String, Object> syncQueryTracked(String userId, String workerId, String prompt,
                                                 String cwd, String codexThreadId, int maxTurns,
                                                 String model, String sessionId) {
        workerManagementFacade.validateWorkerOwnership(userId, workerId);
        CodexConfig codexConfig = workerManagementFacade.getCodexConfig(workerId);
        String effectiveModel = model != null ? model : codexConfig != null ? codexConfig.getModel() : null;

        // 创建 codex_tasks 记录
        String taskId = taskService.createTrackedSyncTask(
                userId, workerId, sessionId, prompt, cwd, null, codexThreadId, effectiveModel);

        return executeTrackedSyncTask(taskId, workerId, codexConfig, prompt, cwd,
                codexThreadId, effectiveModel, maxTurns, sessionId);
    }

    private Map<String, Object> executeTrackedSyncTask(String taskId, String workerId,
                                                        CodexConfig codexConfig,
                                                        String prompt, String cwd,
                                                        String codexThreadId, String model,
                                                        int maxTurns, String sessionId) {
        Map<String, Object> result;
        try {
            result = doSyncQuery(taskId, workerId, codexConfig, prompt, cwd,
                    codexThreadId, model, maxTurns, null);

            String workerTaskId = (String) result.get("workerTaskId");
            String newCodexThreadId = (String) result.get("codexThreadId");
            boolean resultObserved = Boolean.TRUE.equals(result.get("resultObserved"));
            boolean terminalObserved = Boolean.TRUE.equals(result.get("terminalObserved"));
            String terminalStatus = (String) result.get("terminalStatus");
            String error = (String) result.get("error");
            if (resultObserved) {
                taskService.completeTask(taskId, workerTaskId, newCodexThreadId,
                        (String) result.get("resultText"),
                        toBigDecimal(result.get("costUsd")),
                        toLong(result.get("inputTokens")),
                        toLong(result.get("outputTokens")),
                        toLong(result.get("durationMs")),
                        toInteger(result.get("numTurns")),
                        (String) result.get("model"));
            } else if (terminalObserved && "FAILED".equals(terminalStatus)) {
                taskService.failTask(taskId, workerTaskId, newCodexThreadId, truncate(error, 500));
            } else if (terminalObserved && "ABORTED".equals(terminalStatus)) {
                taskService.reconcileAbortedTask(taskId, workerTaskId, newCodexThreadId);
            } else {
                // A sync transport failure, empty stream, or bare Worker
                // error supplies no proof that the CLI stopped.  Preserve the
                // task and make the uncertainty visible rather than writing a
                // synthetic FAILED/COMPLETED terminal state.
                taskService.markLifecycleAttention(taskId, "PROCESS_UNVERIFIED");
                result.putIfAbsent("error", "CODEX_SYNC_QUERY_UNCONFIRMED");
                result.put("terminalObserved", false);
            }
        } catch (Exception e) {
            String failure = stableFailureCode(e, "CODEX_SYNC_QUERY_FAILED");
            log.error("syncQueryTracked failed: taskId={}, code={}, type={}",
                    taskId, failure, exceptionType(e));
            taskService.markLifecycleAttention(taskId, "PROCESS_UNVERIFIED");
            result = new LinkedHashMap<>();
            result.put("error", "CODEX_SYNC_QUERY_UNCONFIRMED");
            result.put("diagnosticCode", failure);
            result.put("terminalObserved", false);
        }

        result.put("taskId", taskId);
        return result;
    }

    /**
     * 内部 syncQuery 实现
     */
    private Map<String, Object> doSyncQuery(String taskId, String workerId, CodexConfig codexConfig,
                                             String prompt, String cwd,
                                             String codexThreadId, String model, int maxTurns,
                                             String apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            if (codexConfig == null || codexConfig.getBaseUrl() == null || codexConfig.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("Codex not configured for worker: " + workerId);
            }
            CodexWorkerClient client = clientFactory.getOrCreate(
                    workerId + ":codex", codexConfig.getBaseUrl(), codexConfig.getAuthToken());
            long timeoutSeconds = Math.max(60, maxTurns * 30L);

            String effectiveModel = model != null ? model : codexConfig != null ? codexConfig.getModel() : null;

            reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> events =
                    client.streamQuery(prompt, cwd, codexThreadId, effectiveModel, maxTurns,
                            null, null, apiKey, null, null);

            SyncQueryAccumulator state = events
                    .reduce(new SyncQueryAccumulator(codexThreadId, null), (acc, sse) -> {
                        consumeSyncEvent(acc, sse);
                        return acc;
                    })
                    .block(Duration.ofSeconds(timeoutSeconds));
            if (state == null) {
                state = new SyncQueryAccumulator(codexThreadId, null);
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
            result.put("resultObserved", state.resultEventObserved);
            result.put("terminalObserved", state.terminalObserved);
            result.put("terminalStatus", state.terminalStatus);
            if (state.errorEventObserved && state.terminalObserved) {
                result.put("error", stableWorkerError(state.error));
            } else if (!state.resultEventObserved) {
                result.put("error", "CODEX_SYNC_QUERY_UNCONFIRMED");
            }

            log.info("doSyncQuery completed: workerId={}, events={}, hasResult={}, hasError={}",
                    workerId, state.eventCount, state.getResultText() != null, result.containsKey("error"));

        } catch (Exception e) {
            String failure = stableFailureCode(e, "CODEX_SYNC_QUERY_FAILED");
            log.error("syncQuery failed: workerId={}, code={}, type={}",
                    workerId, failure, exceptionType(e));
            result.put("error", "CODEX_SYNC_QUERY_UNCONFIRMED");
            result.put("diagnosticCode", failure);
            result.put("terminalObserved", false);
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

    private Map<String, Object> taskToMap(DispatchTaskDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", dto.getTaskId());
        map.put("workerTaskId", dto.getWorkerTaskId());
        map.put("runtimeId", dto.getRuntimeId());
        map.put("runtimeRevision", dto.getRuntimeRevision());
        map.put("runtimeType", dto.getRuntimeType());
        map.put("runtimeInstanceId", dto.getRuntimeInstanceId());
        map.put("routingEpoch", dto.getRoutingEpoch());
        map.put("runtimeAcceptanceState", dto.getRuntimeAcceptanceState());
        map.put("sessionId", dto.getSessionId());
        map.put("workerId", dto.getWorkerId());
        map.put("providerType", dto.getProviderType());
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
        map.put("error", dto.getError());
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
                acc.resultEventObserved = true;
                acc.resultText = event.getContent() != null ? event.getContent() : event.getResult();
                acc.costUsd = event.getCostUsd();
                acc.durationMs = event.getDurationMs();
                acc.inputTokens = event.getInputTokens();
                acc.outputTokens = event.getOutputTokens();
                acc.numTurns = event.getNumTurns();
            } else if ("assistant_text".equals(event.getType())
                    && !"text_delta".equals(event.getSubtype())
                    && !"commentary".equals(event.getSubtype())) {
                // App Server text_delta carries only an incremental fragment;
                // the subsequent completed item carries the complete answer.
                // Keep the latest completed item instead of joining every
                // assistant event from the thread into one giant result.
                acc.assistantText = event.getContent();
            } else if ("error".equals(event.getType())) {
                acc.errorEventObserved = true;
                acc.error = event.getError();
                if (Boolean.TRUE.equals(event.getTerminalObserved())
                        && ("FAILED".equals(event.getTerminalStatus())
                        || "ABORTED".equals(event.getTerminalStatus()))) {
                    acc.terminalObserved = true;
                    acc.terminalStatus = event.getTerminalStatus();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse sync query event: type={}", exceptionType(e));
        }
    }

    private String stableWorkerError(String error) {
        return error != null && error.matches("[A-Z][A-Z0-9_]{0,127}")
                ? error
                : "CODEX_RUNTIME_REMOTE_ERROR";
    }

    private String stableFailureCode(Throwable error, String fallback) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String candidate = message.split(":", 2)[0].trim();
                if (candidate.matches("[A-Z][A-Z0-9_]{0,127}")) {
                    return candidate;
                }
            }
            current = current.getCause();
        }
        return fallback;
    }

    private String exceptionType(Throwable error) {
        return error != null ? error.getClass().getSimpleName() : "UnknownException";
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
        private boolean resultEventObserved;
        private boolean errorEventObserved;
        private boolean terminalObserved;
        private String terminalStatus;
        private int eventCount;
        private String assistantText;

        private SyncQueryAccumulator(String initialCodexThreadId, String workerTaskId) {
            this.codexThreadId = initialCodexThreadId;
            this.workerTaskId = workerTaskId;
        }

        private String getResultText() {
            if (resultText != null) {
                return resultText;
            }
            return assistantText;
        }

    }
}

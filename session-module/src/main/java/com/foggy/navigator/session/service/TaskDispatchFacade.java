package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 统一任务分发 Facade —— 所有外部入口（前端 / OpenAPI / A2A）的唯一任务操作层。
 * <p>
 * 职责：
 * <ol>
 *   <li>验证/建立会话-Agent 绑定</li>
 *   <li>通过 UnifiedAgentResolver 解析目标 A2aAgent</li>
 *   <li>构造 A2aMessage 并委派到 Agent 执行</li>
 *   <li>返回统一 DispatchTaskDTO</li>
 * </ol>
 * <p>
 * Controller 只依赖本 Facade，不允许直接接触 A2aAgent / Provider / Worker。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatchFacade {

    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    private final List<TaskQueryProvider> taskQueryProviders;

    @Autowired(required = false)
    @Nullable
    private LlmModelManager llmModelManager;

    /**
     * 创建任务。
     * <p>
     * 1. 解析 agentId（显式 或 从 modelConfigId 兼容推导）
     * 2. 验证/建立 Session ↔ Agent 绑定
     * 3. 构造 A2aMessage，调用 A2aAgent.sendTask()
     * 4. 返回统一 DTO
     */
    public DispatchTaskDTO createTask(TaskDispatchRequest request, AgentResolveContext context) {
        // 兼容推导：前端可能只传 modelConfigId 而不传 agentId
        String rawAgentId = request.getAgentId();
        if ((rawAgentId == null || rawAgentId.isBlank()) && request.getModelConfigId() != null) {
            rawAgentId = resolveAgentIdFromModelConfig(request.getModelConfigId());
            request.setAgentId(rawAgentId);
        }
        if (rawAgentId == null || rawAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required (provide agentId or modelConfigId)");
        }
        final String agentId = rawAgentId;

        // 获取 providerType
        String providerType = agentResolver.getProviderType(agentId, context)
                .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + agentId));

        // 绑定校验
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            bindingService.getOrBind(request.getSessionId(), agentId, providerType, "EXPLICIT_AGENT");
        }

        // 解析 Agent
        A2aAgent agent = agentResolver.resolveAgent(agentId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + agentId));

        // 构造 A2aMessage
        A2aMessage message = buildMessage(request);

        // 执行
        A2aTask a2aTask = agent.sendTask(message);
        log.info("Dispatched task via Facade: agentId={}, providerType={}, taskId={}",
                agentId, providerType, a2aTask.getId());

        return toDispatchDTO(a2aTask, agentId, providerType, request);
    }

    /**
     * 查询单个任务（遍历所有 TaskQueryProvider）
     */
    public Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
        for (TaskQueryProvider provider : taskQueryProviders) {
            Optional<DispatchTaskDTO> result = context.getUserId() != null
                    ? provider.getTaskByIdAndUser(taskId, context.getUserId())
                    : provider.getTaskById(taskId);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /**
     * 按会话查询任务列表（根据 session 绑定的 providerType 路由到对应 Provider）
     */
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return List.of();

        String providerType = session.getProviderType();
        if (providerType != null) {
            // 精确匹配 provider
            return taskQueryProviders.stream()
                    .filter(p -> providerType.equals(p.getProviderType()))
                    .findFirst()
                    .map(p -> p.listTasksBySession(sessionId))
                    .orElse(List.of());
        }

        // providerType 为空（旧会话），遍历所有 provider
        return taskQueryProviders.stream()
                .flatMap(p -> p.listTasksBySession(sessionId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 聚合所有 Provider 的活跃任务
     */
    public List<DispatchTaskDTO> listActiveTasks(String userId) {
        return taskQueryProviders.stream()
                .flatMap(p -> p.listActiveDispatchTasks(userId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId, String agentId, AgentResolveContext context) {
        A2aAgent agent = agentResolver.resolveAgent(agentId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + agentId));

        // 验证绑定（如有 sessionId）
        if (context.getSessionId() != null) {
            bindingService.validateBinding(context.getSessionId(), agentId);
        }

        agent.cancelTask(taskId);
        log.info("Cancelled task via Facade: taskId={}, agentId={}", taskId, agentId);
    }

    // ── 内部方法 ──

    private A2aMessage buildMessage(TaskDispatchRequest request) {
        List<A2aPart> parts = new ArrayList<>();
        parts.add(A2aPart.text(request.getPrompt()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "sessionId", request.getSessionId());
        putIfNotBlank(metadata, "workerId", request.getWorkerId());
        putIfNotBlank(metadata, "cwd", request.getCwd());
        putIfNotBlank(metadata, "directoryId", request.getDirectoryId());
        putIfNotBlank(metadata, "model", request.getModel());
        putIfNotBlank(metadata, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(metadata, "permissionMode", request.getPermissionMode());
        putIfNotBlank(metadata, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(metadata, "agentTeamsJson", request.getAgentTeamsJson());
        putIfNotBlank(metadata, "codexThreadId", request.getCodexThreadId());
        if (request.getMaxTurns() != null) {
            metadata.put("maxTurns", request.getMaxTurns());
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            metadata.put("images", request.getImages());
        }

        return A2aMessage.builder()
                .role("user")
                .parts(parts)
                .contextId(request.getContextId())
                .metadata(metadata)
                .build();
    }

    private DispatchTaskDTO toDispatchDTO(A2aTask a2aTask, String agentId, String providerType,
                                           TaskDispatchRequest request) {
        DispatchTaskDTO.DispatchTaskDTOBuilder builder = DispatchTaskDTO.builder()
                .taskId(a2aTask.getId())
                .agentId(agentId)
                .providerType(providerType)
                .contextId(a2aTask.getContextId());

        // 映射状态
        if (a2aTask.getStatus() != null) {
            builder.status(mapA2aState(a2aTask.getStatus().getState()));
        }

        // 从 A2aTask metadata 提取扩展字段
        Map<String, Object> meta = a2aTask.getMetadata();
        if (meta != null) {
            builder.sessionId(strVal(meta, "sessionId"))
                    .workerId(strVal(meta, "workerId"))
                    .workerTaskId(strVal(meta, "workerTaskId"))
                    .directoryId(strVal(meta, "directoryId"))
                    .claudeSessionId(strVal(meta, "claudeSessionId"))
                    .codexThreadId(strVal(meta, "codexThreadId"));
        }

        // 从 request 补充（如果 metadata 里没有）
        if (request != null) {
            DispatchTaskDTO dto = builder.build();
            if (dto.getSessionId() == null) builder.sessionId(request.getSessionId());
            if (dto.getWorkerId() == null) builder.workerId(request.getWorkerId());
            if (dto.getDirectoryId() == null) builder.directoryId(request.getDirectoryId());
            builder.prompt(request.getPrompt())
                    .cwd(request.getCwd())
                    .model(request.getModel());
        }

        // 从 artifacts 提取 resultText
        if (a2aTask.getArtifacts() != null && !a2aTask.getArtifacts().isEmpty()) {
            a2aTask.getArtifacts().stream()
                    .flatMap(art -> art.getParts() != null ? art.getParts().stream() : java.util.stream.Stream.empty())
                    .filter(p -> "text".equals(p.getType()) && p.getText() != null)
                    .findFirst()
                    .ifPresent(p -> builder.resultText(p.getText()));
        }

        // 错误信息
        if (a2aTask.getStatus() != null && a2aTask.getStatus().getDescription() != null) {
            A2aTaskState state = a2aTask.getStatus().getState();
            if (state == A2aTaskState.FAILED) {
                builder.errorMessage(a2aTask.getStatus().getDescription());
            }
        }

        return builder.build();
    }

    private String mapA2aState(A2aTaskState state) {
        if (state == null) return "PENDING";
        return switch (state) {
            case SUBMITTED -> "PENDING";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "ABORTED";
        };
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * 从 modelConfigId 推导 agentId（兼容旧前端）。
     * <p>
     * 规则：workerBackend == "OPENAI_CODEX" → "codex-worker"，其余 → "claude-worker"
     */
    private String resolveAgentIdFromModelConfig(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return "claude-worker"; // 默认走 Claude
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .map(config -> "OPENAI_CODEX".equals(config.getWorkerBackend()) ? "codex-worker" : "claude-worker")
                .orElse("claude-worker");
    }
}

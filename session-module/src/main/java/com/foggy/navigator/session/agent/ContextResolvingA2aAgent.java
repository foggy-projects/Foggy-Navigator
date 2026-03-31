package com.foggy.navigator.session.agent;

import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.exception.ContextAgentMismatchException;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * A2aAgent 装饰者 — 在委托 InnerA2aAgent 前解析上下文（contextId/contextAlias → session）
 * <p>
 * 共享基础设施：位于 session-module，供 claude-worker-agent / codex-worker-agent 等 addon 复用。
 */
public class ContextResolvingA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ContextResolvingA2aAgent.class);
    private static final int CONTEXT_TTL_HOURS = 24;

    private final InnerA2aAgent inner;
    private final AgentContextStore contextStore;
    private final CodingAgentEntity agentEntity;
    private final String providerType;

    public ContextResolvingA2aAgent(InnerA2aAgent inner, AgentContextStore contextStore,
                                    CodingAgentEntity agentEntity, String providerType) {
        this.inner = inner;
        this.contextStore = contextStore;
        this.agentEntity = agentEntity;
        this.providerType = providerType;
    }

    /**
     * 便捷构造：自动从 agentEntity.agentType 推导 providerType。
     * LOCAL_CLAUDE_WORKER → "claude-worker"，LOCAL_CODEX_WORKER → "codex-worker"，其他 → "worker"
     */
    public ContextResolvingA2aAgent(InnerA2aAgent inner, AgentContextStore contextStore,
                                    CodingAgentEntity agentEntity) {
        this(inner, contextStore, agentEntity, deriveProviderType(agentEntity));
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return inner.getAgentCard();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        String userId = agentEntity.getUserId();
        String agentId = agentEntity.getAgentId();
        String contextId = message.getContextId();
        String contextAlias = message.getContextAlias();

        // 1. Resolve context: contextId or contextAlias → AgentConversationContextEntity
        String agentSessionRef = null;
        String navigatorSessionId = null;
        String resolvedContextId = contextId;

        if (contextStore != null) {
            // 1a. Try contextId first (direct PK lookup)
            if (contextId != null && !contextId.isBlank()) {
                try {
                    agentSessionRef = contextStore.findSessionRefForAgent(
                            contextId, userId, agentId, CONTEXT_TTL_HOURS).orElse(null);
                } catch (ContextAgentMismatchException e) {
                    log.warn("A2A context mismatch: {}", e.getMessage());
                    return buildFailedTask(contextId, e.getMessage());
                }
                if (agentSessionRef != null) {
                    log.debug("Resolved contextId={} → agentSessionRef={}", contextId, agentSessionRef);
                }
            }

            // 1b. Try contextAlias (business alias lookup)
            if (contextAlias != null && !contextAlias.isBlank()) {
                Optional<AgentConversationContextEntity> existing =
                        contextStore.findByAlias(contextAlias, userId, agentId, CONTEXT_TTL_HOURS);
                if (existing.isPresent()) {
                    AgentConversationContextEntity ctx = existing.get();
                    resolvedContextId = ctx.getContextId();
                    agentSessionRef = ctx.getAgentSessionRef();
                    navigatorSessionId = ctx.getNavigatorSessionId();
                    log.debug("Resolved contextAlias={} → contextId={}, agentSessionRef={}, navigatorSessionId={}",
                            contextAlias, resolvedContextId, agentSessionRef, navigatorSessionId);
                }
                // If not found: will create new context after task creation
            }
        }

        // Generate contextId if not resolved yet
        if (resolvedContextId == null || resolvedContextId.isBlank()) {
            resolvedContextId = IdGenerator.shortId();
        }

        // 1c. 并发保护：检查 agent session 是否有正在运行的任务
        if (agentSessionRef != null && inner.isSessionBusy(agentSessionRef)) {
            log.warn("Session busy: agentSessionRef={}, contextId={}", agentSessionRef, resolvedContextId);
            return buildFailedTask(resolvedContextId,
                    "Session has a running task, please wait for it to complete or cancel it first.");
        }

        // 2. Build A2aContext
        A2aContext context = A2aContext.builder()
                .message(message)
                .contextId(resolvedContextId)
                .contextAlias(contextAlias)
                .agentSessionRef(agentSessionRef)
                .navigatorSessionId(navigatorSessionId)
                .userId(userId)
                .tenantId(agentEntity.getTenantId())
                .agentId(agentId)
                .build();

        // 3. Delegate to inner agent
        A2aTask task = inner.sendTask(context);

        // 4. Save/update context after task creation
        if (contextStore != null && task.getStatus() != null
                && task.getStatus().getState() != A2aTaskState.FAILED) {
            String taskAgentSessionRef = null;
            String taskNavigatorSessionId = navigatorSessionId;
            if (task.getMetadata() != null) {
                Object csid = task.getMetadata().get("claudeSessionId");
                if (csid == null) csid = task.getMetadata().get("codexThreadId");
                if (csid != null) taskAgentSessionRef = csid.toString();
                Object nsid = task.getMetadata().get("sessionId");
                if (nsid != null) taskNavigatorSessionId = nsid.toString();
            }
            contextStore.saveSessionRefFull(resolvedContextId, providerType,
                    taskAgentSessionRef, taskNavigatorSessionId,
                    userId, agentId, contextAlias);
        }

        // Ensure contextId is on the returned task
        if (task.getContextId() == null) {
            task.setContextId(resolvedContextId);
        }

        return task;
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        return inner.getTask(taskId);
    }

    @Override
    public void cancelTask(String taskId) {
        inner.cancelTask(taskId);
    }

    private A2aTask buildFailedTask(String contextId, String description) {
        return A2aTask.builder()
                .id("error-" + IdGenerator.shortId())
                .contextId(contextId)
                .status(A2aTaskStatus.builder()
                        .state(A2aTaskState.FAILED)
                        .description(description)
                        .timestamp(Instant.now())
                        .build())
                .build();
    }

    private static String deriveProviderType(CodingAgentEntity entity) {
        if (entity == null || entity.getAgentType() == null) return "worker";
        return switch (entity.getAgentType()) {
            case "LOCAL_CLAUDE_WORKER" -> "claude-worker";
            case "LOCAL_CODEX_WORKER" -> "codex-worker";
            default -> "worker";
        };
    }
}

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A2aAgent 装饰者 — 在委托 InnerA2aAgent 前解析上下文（contextId/contextAlias → session）
 * <p>
 * 共享基础设施：位于 session-module，供 claude-worker-agent / codex-worker-agent 等 addon 复用。
 */
public class ContextResolvingA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(ContextResolvingA2aAgent.class);
    private static final int CONTEXT_TTL_HOURS = 24;
    private static final String META_SYSTEM_PROMPT = "systemPrompt";
    private static final String META_FIRST_MSG = "firstMsg";
    private static final String META_FIRST_MSG_APPLIED = "firstMsgApplied";
    private static final String INITIAL_MESSAGE_PREFIX = "[Initial Message]\n";

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
                    Optional<AgentConversationContextEntity> existing = contextStore.findContextForAgent(
                            contextId, userId, agentId, CONTEXT_TTL_HOURS);
                    if (existing.isPresent()) {
                        AgentConversationContextEntity ctx = existing.get();
                        agentSessionRef = ctx.getAgentSessionRef();
                        navigatorSessionId = ctx.getNavigatorSessionId();
                    }
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

        boolean firstTurn = navigatorSessionId == null || navigatorSessionId.isBlank();
        A2aMessage effectiveMessage = applyPromptPolicy(message, firstTurn, resolvedContextId);
        if (effectiveMessage == null) {
            return buildFailedTask(resolvedContextId,
                    "Agent does not support systemPrompt; use firstMsg for first-turn visible context instead.");
        }

        // 2. Build A2aContext
        A2aContext context = A2aContext.builder()
                .message(effectiveMessage)
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
            String taskAgentSessionRef = agentSessionRef;
            String taskNavigatorSessionId = navigatorSessionId;
            if (task.getMetadata() != null) {
                Object csid = task.getMetadata().get("claudeSessionId");
                if (csid == null) csid = task.getMetadata().get("codexThreadId");
                if (csid != null && !csid.toString().isBlank()) taskAgentSessionRef = csid.toString();
                Object nsid = task.getMetadata().get("sessionId");
                if (nsid != null && !nsid.toString().isBlank()) taskNavigatorSessionId = nsid.toString();
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

    private A2aMessage applyPromptPolicy(A2aMessage message, boolean firstTurn, String contextId) {
        Map<String, Object> metadata = message.getMetadata() != null
                ? new HashMap<>(message.getMetadata())
                : new HashMap<>();
        String systemPrompt = stringMeta(metadata.get(META_SYSTEM_PROMPT));
        String firstMsg = stringMeta(metadata.get(META_FIRST_MSG));

        if (systemPrompt != null && !supportsSystemPrompt()) {
            log.warn("Agent {} rejected unsupported systemPrompt for contextId={}", agentEntity.getAgentId(), contextId);
            return null;
        }

        boolean firstMsgApplied = false;
        List<A2aPart> parts = message.getParts();
        if (firstTurn && firstMsg != null && parts != null && !parts.isEmpty()) {
            parts = prependFirstMsg(parts, firstMsg);
            firstMsgApplied = true;
        }
        metadata.put(META_FIRST_MSG_APPLIED, firstMsgApplied);

        return A2aMessage.builder()
                .role(message.getRole())
                .parts(parts)
                .taskId(message.getTaskId())
                .contextId(message.getContextId())
                .contextAlias(message.getContextAlias())
                .metadata(metadata)
                .build();
    }

    private boolean supportsSystemPrompt() {
        A2aAgentCard card = inner.getAgentCard();
        return card != null
                && card.getCapabilities() != null
                && Boolean.TRUE.equals(card.getCapabilities().getSupportsSystemPrompt());
    }

    private List<A2aPart> prependFirstMsg(List<A2aPart> parts, String firstMsg) {
        List<A2aPart> effective = new ArrayList<>(parts.size());
        boolean injected = false;
        for (A2aPart part : parts) {
            if (!injected && part != null && "text".equals(part.getType())) {
                String text = part.getText() != null ? part.getText() : "";
                if (text.startsWith(INITIAL_MESSAGE_PREFIX)) {
                    effective.add(part);
                } else {
                    effective.add(A2aPart.text(INITIAL_MESSAGE_PREFIX + firstMsg + "\n\n[User Message]\n" + text));
                }
                injected = true;
            } else {
                effective.add(part);
            }
        }
        if (!injected) {
            effective.add(0, A2aPart.text(INITIAL_MESSAGE_PREFIX + firstMsg));
        }
        return effective;
    }

    private String stringMeta(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
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

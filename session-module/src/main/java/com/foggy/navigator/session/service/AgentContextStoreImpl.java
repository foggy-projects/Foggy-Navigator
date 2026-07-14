package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.exception.ContextAgentMismatchException;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.spi.agent.AgentContextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

@Component
@RequiredArgsConstructor
public class AgentContextStoreImpl implements AgentContextStore {

    private static final String ACCESS_DENIED_MESSAGE = "Resource access denied";

    private final AgentConversationContextRepository repository;
    private final AgentContextOwnershipClaimWriter ownershipClaimWriter;

    @Override
    public Optional<String> findSessionRef(String contextId, String userId) {
        return repository.findByContextIdAndUserId(contextId, userId)
                .filter(e -> e.getAgentSessionRef() != null)
                .map(AgentConversationContextEntity::getAgentSessionRef);
    }

    @Override
    public Optional<String> findSessionRefForAgent(String contextId, String userId,
                                                   String expectedAgentId) {
        return findContextForAgent(contextId, userId, expectedAgentId)
                .map(AgentConversationContextEntity::getAgentSessionRef);
    }

    @Override
    public Optional<AgentConversationContextEntity> findContextForAgent(
            String contextId, String userId, String expectedAgentId) {
        Optional<AgentConversationContextEntity> opt = repository.findByContextIdAndUserId(contextId, userId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        AgentConversationContextEntity e = opt.get();
        if (e.getTargetAgentId() == null
                || e.getTargetAgentId().isBlank()
                || expectedAgentId == null
                || expectedAgentId.isBlank()) {
            throw new SecurityException(ACCESS_DENIED_MESSAGE);
        }
        if (!e.getTargetAgentId().equals(expectedAgentId)) {
            throw new ContextAgentMismatchException(contextId, e.getTargetAgentId(), expectedAgentId);
        }
        return Optional.of(e);
    }

    @Override
    public void saveSessionRef(String contextId, String agentType,
                               String agentSessionRef, String userId, String targetAgentId) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversationContextEntity entity = newContext(
                contextId, userId, targetAgentId, agentType, agentSessionRef, now);
        saveOrUpdateOwned(entity, userId, targetAgentId,
                () -> repository.updateSessionRefIfOwned(
                        contextId, agentType, agentSessionRef,
                        userId, targetAgentId, now));
    }

    @Override
    public Optional<AgentConversationContextEntity> findByAlias(
            String contextAlias, String userId, String targetAgentId) {
        return repository.findByContextAliasAndUserIdAndTargetAgentId(contextAlias, userId, targetAgentId);
    }

    @Override
    public void saveSessionRefFull(String contextId, String agentType,
            String agentSessionRef, String navigatorSessionId,
            String userId, String targetAgentId, String contextAlias) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversationContextEntity entity = newContext(
                contextId, userId, targetAgentId, agentType, agentSessionRef, now);
        entity.setNavigatorSessionId(navigatorSessionId);
        entity.setContextAlias(contextAlias);
        saveOrUpdateOwned(entity, userId, targetAgentId,
                () -> repository.updateSessionRefFullIfOwned(
                        contextId, agentType, agentSessionRef, navigatorSessionId,
                        userId, targetAgentId, contextAlias, now));
    }

    private AgentConversationContextEntity newContext(
            String contextId, String userId, String targetAgentId,
            String agentType, String agentSessionRef, LocalDateTime now) {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId(contextId);
        entity.setUserId(userId);
        entity.setTargetAgentId(targetAgentId);
        entity.setAgentType(agentType);
        entity.setAgentSessionRef(agentSessionRef);
        entity.setLastAccessedAt(now);
        return entity;
    }

    private void saveOrUpdateOwned(
            AgentConversationContextEntity candidate,
            String userId,
            String targetAgentId,
            IntSupplier ownedUpdate) {
        Optional<AgentConversationContextEntity> existing =
                repository.findById(candidate.getContextId());
        if (existing.isPresent()) {
            validateBinding(existing.get(), userId, targetAgentId);
            updateOwnedOrFail(candidate.getContextId(), userId, targetAgentId, ownedUpdate);
            return;
        }

        try {
            ownershipClaimWriter.insert(candidate);
        } catch (DataIntegrityViolationException insertFailure) {
            AgentConversationContextEntity winner = repository.findById(candidate.getContextId())
                    .orElseThrow(() -> insertFailure);
            validateBinding(winner, userId, targetAgentId);
            updateOwnedOrFail(candidate.getContextId(), userId, targetAgentId, ownedUpdate);
        }
    }

    private void updateOwnedOrFail(
            String contextId,
            String userId,
            String targetAgentId,
            IntSupplier ownedUpdate) {
        if (ownedUpdate.getAsInt() > 0) {
            return;
        }

        // 条件更新为 0 说明记录在 compare/update 窗口被删除或替换；
        // 重读后再次校验，始终 fail closed，不退化为无条件 merge/save。
        repository.findById(contextId)
                .ifPresent(latest -> validateBinding(latest, userId, targetAgentId));
        throw new IllegalStateException("Context changed concurrently; retry the request");
    }

    private void validateBinding(
            AgentConversationContextEntity entity,
            String userId,
            String targetAgentId) {
        if (!Objects.equals(entity.getUserId(), userId)) {
            throw new SecurityException(ACCESS_DENIED_MESSAGE);
        }
        if (!Objects.equals(entity.getTargetAgentId(), targetAgentId)) {
            throw new ContextAgentMismatchException(
                    entity.getContextId(), entity.getTargetAgentId(), targetAgentId);
        }
    }

    @Override
    public void deleteByNavigatorSessionId(String navigatorSessionId) {
        if (navigatorSessionId == null || navigatorSessionId.isBlank()) {
            return;
        }
        repository.deleteByNavigatorSessionId(navigatorSessionId);
    }
}

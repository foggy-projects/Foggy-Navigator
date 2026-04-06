package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.exception.ContextAgentMismatchException;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.spi.agent.AgentContextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AgentContextStoreImpl implements AgentContextStore {

    private final AgentConversationContextRepository repository;

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
        // Agent 不匹配 → 抛异常
        if (e.getTargetAgentId() != null && !e.getTargetAgentId().equals(expectedAgentId)) {
            throw new ContextAgentMismatchException(contextId, e.getTargetAgentId(), expectedAgentId);
        }
        return Optional.of(e);
    }

    @Override
    public void saveSessionRef(String contextId, String agentType,
                               String agentSessionRef, String userId, String targetAgentId) {
        AgentConversationContextEntity entity = repository.findById(contextId).orElse(null);
        if (entity == null) {
            entity = new AgentConversationContextEntity();
            entity.setContextId(contextId);
            entity.setUserId(userId);
            entity.setTargetAgentId(targetAgentId);
        }
        entity.setAgentType(agentType);
        entity.setAgentSessionRef(agentSessionRef);
        entity.setLastAccessedAt(LocalDateTime.now());
        repository.save(entity);
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
        AgentConversationContextEntity entity = repository.findById(contextId).orElse(null);
        if (entity == null) {
            entity = new AgentConversationContextEntity();
            entity.setContextId(contextId);
            entity.setUserId(userId);
            entity.setTargetAgentId(targetAgentId);
        }
        entity.setAgentType(agentType);
        entity.setAgentSessionRef(agentSessionRef);
        entity.setNavigatorSessionId(navigatorSessionId);
        entity.setContextAlias(contextAlias);
        entity.setLastAccessedAt(LocalDateTime.now());
        repository.save(entity);
    }

    @Override
    public void deleteByNavigatorSessionId(String navigatorSessionId) {
        if (navigatorSessionId == null || navigatorSessionId.isBlank()) {
            return;
        }
        repository.deleteByNavigatorSessionId(navigatorSessionId);
    }
}

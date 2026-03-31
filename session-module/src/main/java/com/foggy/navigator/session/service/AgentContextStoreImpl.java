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
    public Optional<String> findSessionRef(String contextId, String userId, int ttlHours) {
        return repository.findByContextIdAndUserId(contextId, userId)
                .filter(e -> e.getAgentSessionRef() != null)
                .filter(e -> e.getLastAccessedAt().isAfter(LocalDateTime.now().minusHours(ttlHours)))
                .map(AgentConversationContextEntity::getAgentSessionRef);
    }

    @Override
    public Optional<String> findSessionRefForAgent(String contextId, String userId,
                                                   String expectedAgentId, int ttlHours) {
        Optional<AgentConversationContextEntity> opt = repository.findByContextIdAndUserId(contextId, userId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        AgentConversationContextEntity e = opt.get();
        // TTL 过期 → 视为不存在
        if (e.getLastAccessedAt().isBefore(LocalDateTime.now().minusHours(ttlHours))) {
            return Optional.empty();
        }
        // Agent 不匹配 → 抛异常
        if (e.getTargetAgentId() != null && !e.getTargetAgentId().equals(expectedAgentId)) {
            throw new ContextAgentMismatchException(contextId, e.getTargetAgentId(), expectedAgentId);
        }
        return Optional.ofNullable(e.getAgentSessionRef());
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
}

package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentConversationContextRepository
        extends JpaRepository<AgentConversationContextEntity, String> {

    Optional<AgentConversationContextEntity> findByContextIdAndUserId(String contextId, String userId);
}

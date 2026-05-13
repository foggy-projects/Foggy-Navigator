package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface BusinessAgentSessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId, Pageable pageable);

    @Query("SELECT m FROM SessionMessageEntity m WHERE m.sessionId = :sessionId AND m.createdAt > :afterTime ORDER BY m.createdAt ASC")
    List<SessionMessageEntity> findBySessionIdAfterTime(String sessionId, LocalDateTime afterTime, Pageable pageable);
}

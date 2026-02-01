package com.foggy.navigator.coding.agent.api.repository;

import com.foggy.navigator.coding.agent.api.model.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, Long> {

    List<EventEntity> findByConversationIdOrderByTimestampAsc(String conversationId);

    List<EventEntity> findByConversationIdAndKindOrderByTimestampAsc(String conversationId, EventEntity.EventKind kind);

    List<EventEntity> findByConversationIdAndTimestampAfterOrderByTimestampAsc(String conversationId, LocalDateTime timestamp);

    @Query("SELECT e FROM EventEntity e WHERE e.conversationId = :conversationId ORDER BY e.timestamp ASC")
    List<EventEntity> findTopNByConversationIdOrderByTimestampAsc(@Param("conversationId") String conversationId);
}

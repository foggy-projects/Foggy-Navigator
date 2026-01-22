package com.foggy.navigator.api.repository;

import com.foggy.navigator.api.model.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findByConversationIdOrderByTimestampDesc(String conversationId);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :conversationId ORDER BY m.timestamp DESC")
    List<MessageEntity> findTopNByConversationIdOrderByTimestampDesc(@Param("conversationId") String conversationId);
}

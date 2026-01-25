package com.foggy.navigator.api.repository;

import com.foggy.navigator.api.model.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    Optional<ConversationEntity> findByConversationId(String conversationId);

    boolean existsByConversationId(String conversationId);

    boolean existsBySandboxId(String sandboxId);

    List<ConversationEntity> findByUserId(String userId);

    List<ConversationEntity> findByUserIdAndProjectId(String userId, String projectId);

    List<ConversationEntity> findByUserIdAndStatus(String userId, ConversationEntity.ConversationStatus status);

    List<ConversationEntity> findByStatus(ConversationEntity.ConversationStatus status);

    List<ConversationEntity> findByStatusAndUpdatedAtBefore(ConversationEntity.ConversationStatus status, LocalDateTime updatedAtBefore);

    List<ConversationEntity> findByCreatedAtBefore(LocalDateTime createdAtBefore);

    void deleteByConversationId(String conversationId);
}

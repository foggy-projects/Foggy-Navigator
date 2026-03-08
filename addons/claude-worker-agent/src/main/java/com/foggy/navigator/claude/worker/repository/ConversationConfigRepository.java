package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationConfigRepository extends JpaRepository<ConversationConfigEntity, Long> {

    Optional<ConversationConfigEntity> findBySessionId(String sessionId);

    List<ConversationConfigEntity> findBySessionIdIn(List<String> sessionIds);

    List<ConversationConfigEntity> findByUserIdAndWorkerIdAndPinnedTrue(String userId, String workerId);

    @Query("SELECT c.sessionId FROM ConversationConfigEntity c " +
           "WHERE c.userId = :userId AND c.interactionState = :state")
    List<String> findSessionIdsByInteractionState(@Param("userId") String userId,
                                                   @Param("state") String state);

    @Query("SELECT c.sessionId FROM ConversationConfigEntity c " +
           "WHERE c.userId = :userId AND c.interactionState IN :states")
    List<String> findSessionIdsByInteractionStateIn(@Param("userId") String userId,
                                                     @Param("states") List<String> states);

    @Query("SELECT c.sessionId FROM ConversationConfigEntity c " +
           "WHERE c.interactionState IN :states")
    List<String> findSessionIdsByStates(@Param("states") List<String> states);

    List<ConversationConfigEntity> findByInteractionStateIn(List<String> states);

    void deleteBySessionId(String sessionId);
}

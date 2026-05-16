package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, String> {

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.interactionState = :state AND s.deletedAt IS NULL")
    List<String> findSessionIdsByInteractionState(@Param("userId") String userId,
                                                  @Param("state") String state);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.interactionState IN :states AND s.deletedAt IS NULL")
    List<String> findSessionIdsByInteractionStateIn(@Param("userId") String userId,
                                                    @Param("states") List<String> states);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.interactionState IN :states AND s.deletedAt IS NULL")
    List<String> findSessionIdsByStates(@Param("states") List<String> states);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.interactionState IN :states " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED'")
    List<SessionEntity> findByInteractionStateIn(@Param("states") List<String> states);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<String> findSessionIdsByTitleKeyword(@Param("userId") String userId,
                                              @Param("keyword") String keyword);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND LOWER(COALESCE(s.tagsJson, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<String> findSessionIdsByTagKeyword(@Param("userId") String userId,
                                            @Param("keyword") String keyword);

    /**
     * Check if a soft-deleted session exists with the given claudeSessionId in its providerStateJson.
     * Used by syncLocalSessions to skip re-importing sessions that users have deleted.
     */
    @Query("SELECT COUNT(s) > 0 FROM SessionEntity s " +
           "WHERE s.providerStateJson LIKE %:claudeSessionId% AND s.deletedAt IS NOT NULL")
    boolean existsDeletedByClaudeSessionId(@Param("claudeSessionId") String claudeSessionId);

    /**
     * Find all soft-deleted sessions for a given user whose providerStateJson contains the workerId.
     * Used to batch-load deleted claude session IDs during syncLocalSessions.
     */
    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.deletedAt IS NOT NULL " +
           "AND s.providerStateJson LIKE %:workerId% " +
           "AND s.userId = :userId")
    List<SessionEntity> findDeletedByWorkerIdAndUserId(@Param("workerId") String workerId,
                                                       @Param("userId") String userId);

    @Query("SELECT COUNT(s) FROM SessionEntity s " +
           "WHERE s.milestoneId = :milestoneId AND s.userId = :userId AND s.deletedAt IS NULL")
    long countByMilestoneIdAndUserId(@Param("milestoneId") String milestoneId,
                                     @Param("userId") String userId);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.milestoneId = NULL " +
           "WHERE s.milestoneId = :milestoneId AND s.userId = :userId AND s.deletedAt IS NULL")
    int clearMilestoneIdByMilestoneIdAndUserId(@Param("milestoneId") String milestoneId,
                                                @Param("userId") String userId);
}

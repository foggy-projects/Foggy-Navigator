package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, String> {

    interface ResumeStateView {
        String getId();
        String getProviderStateJson();
        String getLatestTaskId();
    }

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    @Query("SELECT s.id AS id, s.providerStateJson AS providerStateJson, " +
           "s.latestTaskId AS latestTaskId " +
           "FROM SessionEntity s WHERE s.id = :id AND s.userId = :userId")
    Optional<ResumeStateView> findResumeStateByIdAndUserId(@Param("id") String id,
                                                           @Param("userId") String userId);

    @Query(value = "SELECT s.id AS id, s.provider_state_json AS providerStateJson, " +
                   "s.latest_task_id AS latestTaskId " +
                   "FROM sessions s WHERE s.id = :id AND s.user_id = :userId FOR UPDATE",
            nativeQuery = true)
    Optional<ResumeStateView> findResumeStateByIdAndUserIdForUpdate(@Param("id") String id,
                                                                    @Param("userId") String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionEntity s WHERE s.id = :id AND s.userId = :userId")
    Optional<SessionEntity> findByIdAndUserIdForUpdate(@Param("id") String id,
                                                       @Param("userId") String userId);

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

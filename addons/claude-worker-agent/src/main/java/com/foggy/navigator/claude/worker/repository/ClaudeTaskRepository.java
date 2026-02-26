package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ClaudeTaskRepository extends JpaRepository<ClaudeTaskEntity, Long> {

    Optional<ClaudeTaskEntity> findByTaskId(String taskId);

    Optional<ClaudeTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    List<ClaudeTaskEntity> findBySessionId(String sessionId);

    List<ClaudeTaskEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<ClaudeTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Page<ClaudeTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<ClaudeTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    Page<ClaudeTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId, Pageable pageable);

    List<ClaudeTaskEntity> findByStatusIn(List<String> statuses);

    List<ClaudeTaskEntity> findByStatusAndCreatedAtBefore(String status, LocalDateTime before);

    boolean existsByClaudeSessionIdAndWorkerId(String claudeSessionId, String workerId);

    List<ClaudeTaskEntity> findByWorkerIdAndUserIdAndDirectoryIdIsNull(String workerId, String userId);

    // Session-level pagination: get distinct sessionIds sorted by max createdAt desc
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.userId = :userId " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByUser(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.sessionId) FROM ClaudeTaskEntity t WHERE t.userId = :userId")
    long countDistinctSessionsByUser(@Param("userId") String userId);

    List<ClaudeTaskEntity> findBySessionIdInAndUserIdOrderByCreatedAtDesc(List<String> sessionIds, String userId);

    // Session-level pagination by directory
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.directoryId = :directoryId AND t.userId = :userId " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByDirectory(@Param("directoryId") String directoryId,
                                                    @Param("userId") String userId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.sessionId) FROM ClaudeTaskEntity t " +
            "WHERE t.directoryId = :directoryId AND t.userId = :userId")
    long countDistinctSessionsByDirectory(@Param("directoryId") String directoryId,
                                          @Param("userId") String userId);
}

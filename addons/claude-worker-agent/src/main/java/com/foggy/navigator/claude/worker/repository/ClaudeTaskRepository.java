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

    List<ClaudeTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

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

    // Session-level pagination filtered to specific sessionIds (for interactionState filtering)
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.userId = :userId " +
            "AND t.sessionId IN :sessionIds " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByUserFilteredBySessionIds(
            @Param("userId") String userId,
            @Param("sessionIds") List<String> sessionIds,
            Pageable pageable);

    // Session-level pagination excluding specific sessionIds (for hiding archived)
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.userId = :userId " +
            "AND t.sessionId NOT IN :excludeSessionIds " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByUserExcludingSessionIds(
            @Param("userId") String userId,
            @Param("excludeSessionIds") List<String> excludeSessionIds,
            Pageable pageable);

    // Session-level pagination by directory
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.directoryId = :directoryId AND t.userId = :userId " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByDirectory(@Param("directoryId") String directoryId,
                                                    @Param("userId") String userId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.sessionId) FROM ClaudeTaskEntity t " +
            "WHERE t.directoryId = :directoryId AND t.userId = :userId")
    long countDistinctSessionsByDirectory(@Param("directoryId") String directoryId,
                                          @Param("userId") String userId);

    // Directory-level pagination filtered to specific sessionIds
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.directoryId = :directoryId AND t.userId = :userId " +
            "AND t.sessionId IN :sessionIds " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByDirectoryFilteredBySessionIds(
            @Param("directoryId") String directoryId,
            @Param("userId") String userId,
            @Param("sessionIds") List<String> sessionIds,
            Pageable pageable);

    // Directory-level pagination excluding specific sessionIds
    @Query("SELECT t.sessionId FROM ClaudeTaskEntity t WHERE t.directoryId = :directoryId AND t.userId = :userId " +
            "AND t.sessionId NOT IN :excludeSessionIds " +
            "GROUP BY t.sessionId ORDER BY MAX(t.createdAt) DESC")
    List<String> findDistinctSessionIdsByDirectoryExcludingSessionIds(
            @Param("directoryId") String directoryId,
            @Param("userId") String userId,
            @Param("excludeSessionIds") List<String> excludeSessionIds,
            Pageable pageable);

    // Count matching sessions in directory filtered by sessionIds
    @Query("SELECT COUNT(DISTINCT t.sessionId) FROM ClaudeTaskEntity t " +
            "WHERE t.directoryId = :directoryId AND t.userId = :userId AND t.sessionId IN :sessionIds")
    long countDistinctSessionsByDirectoryFilteredBySessionIds(
            @Param("directoryId") String directoryId,
            @Param("userId") String userId,
            @Param("sessionIds") List<String> sessionIds);

    /** A2A 幂等：根据 dedupKey 查找指定时间之后的最近一条任务（用于防重复提交） */
    Optional<ClaudeTaskEntity> findFirstByDedupKeyAndCreatedAtAfterOrderByCreatedAtDesc(
            String dedupKey, LocalDateTime after);

    /** Reconciler: 查询指定 Worker 下的活跃任务（排除刚创建的任务以避免误判） */
    List<ClaudeTaskEntity> findByWorkerIdAndStatusInAndCreatedAtBefore(
            String workerId, List<String> statuses, LocalDateTime createdBefore);

    /** Reconciler: 查询指定 Worker 下所有活跃任务（不限创建时间） */
    List<ClaudeTaskEntity> findByWorkerIdAndStatusIn(String workerId, List<String> statuses);

    /** 并发保护：检查某个 Claude 会话在指定 Worker 上是否有指定状态的任务 */
    boolean existsByClaudeSessionIdAndWorkerIdAndStatus(String claudeSessionId, String workerId, String status);

    /** 查询指定 sessionId 列表中每个 session 的最新任务 */
    @Query("SELECT t FROM ClaudeTaskEntity t WHERE t.sessionId IN :sessionIds " +
           "AND t.createdAt = (SELECT MAX(t2.createdAt) FROM ClaudeTaskEntity t2 WHERE t2.sessionId = t.sessionId) " +
           "ORDER BY t.createdAt DESC")
    List<ClaudeTaskEntity> findLatestBySessionIdIn(@Param("sessionIds") List<String> sessionIds);

    // ===== 会话搜索查询 =====

    /** 按 prompt 关键词搜索匹配的 sessionId（不区分大小写） */
    @Query("SELECT DISTINCT t.sessionId FROM ClaudeTaskEntity t " +
           "WHERE t.userId = :userId AND LOWER(t.prompt) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<String> findSessionIdsByPromptKeyword(@Param("userId") String userId,
                                                @Param("keyword") String keyword);

    /** 按 workerId 筛选 sessionId */
    @Query("SELECT DISTINCT t.sessionId FROM ClaudeTaskEntity t " +
           "WHERE t.userId = :userId AND t.workerId = :workerId")
    List<String> findSessionIdsByWorker(@Param("userId") String userId,
                                        @Param("workerId") String workerId);

    /** 按 directoryId 筛选 sessionId */
    @Query("SELECT DISTINCT t.sessionId FROM ClaudeTaskEntity t " +
           "WHERE t.userId = :userId AND t.directoryId = :directoryId")
    List<String> findSessionIdsByDirectory(@Param("userId") String userId,
                                            @Param("directoryId") String directoryId);
}

package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CodexTaskRepository extends JpaRepository<CodexTaskEntity, Long> {

    Optional<CodexTaskEntity> findByTaskId(String taskId);

    Optional<CodexTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from CodexTaskEntity task where task.taskId = :taskId")
    Optional<CodexTaskEntity> findByTaskIdForUpdate(@Param("taskId") String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from CodexTaskEntity task where task.taskId = :taskId and task.userId = :userId")
    Optional<CodexTaskEntity> findByTaskIdAndUserIdForUpdate(
            @Param("taskId") String taskId,
            @Param("userId") String userId);

    boolean existsByCodexThreadIdAndWorkerIdAndUserId(String codexThreadId, String workerId, String userId);

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderType(
            String codexThreadId, String workerId, String userId, String providerType);

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndStatus(
            String codexThreadId, String workerId, String userId, String status);

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndStatusIn(
            String codexThreadId, String workerId, String userId, List<String> statuses);

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeAndStatusIn(
            String codexThreadId, String workerId, String userId,
            String providerType, List<String> statuses);

    @Query("select task.taskId from CodexTaskEntity task " +
           "where task.status in :statuses and " +
           "((task.sessionId = :sessionId and task.userId = :userId) or " +
           "(:codexThreadId is not null and task.codexThreadId = :codexThreadId " +
           "and task.workerId = :workerId and task.userId = :userId " +
           "and task.providerType = :providerType)) " +
           "order by task.id desc")
    List<String> findActiveResumeTaskIds(
            @Param("sessionId") String sessionId,
            @Param("codexThreadId") String codexThreadId,
            @Param("workerId") String workerId,
            @Param("userId") String userId,
            @Param("providerType") String providerType,
            @Param("statuses") List<String> statuses,
            Pageable pageable);

    @Query("select task.taskId from CodexTaskEntity task " +
           "where task.codexThreadId = :codexThreadId " +
           "and task.workerId = :workerId and task.userId = :userId " +
           "and task.providerType = :providerType " +
           "order by task.id desc")
    List<String> findLatestResumeThreadTaskIds(
            @Param("codexThreadId") String codexThreadId,
            @Param("workerId") String workerId,
            @Param("userId") String userId,
            @Param("providerType") String providerType,
            Pageable pageable);

    @Query("select task.taskId from CodexTaskEntity task " +
           "where task.sessionId = :sessionId and task.userId = :userId " +
           "order by task.id desc")
    List<String> findLatestResumeSessionTaskIds(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            Pageable pageable);

    Optional<CodexTaskEntity> findFirstByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeOrderByCreatedAtDesc(
            String codexThreadId, String workerId, String userId, String providerType);

    List<CodexTaskEntity> findBySessionId(String sessionId);

    Optional<CodexTaskEntity> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<CodexTaskEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CodexTaskEntity> findByUserIdAndTenantIdOrderByCreatedAtDesc(String userId, String tenantId);

    Page<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<CodexTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<CodexTaskEntity> findByStatusIn(List<String> statuses);

    List<CodexTaskEntity> findByProviderTypeAndStatusIn(String providerType, List<String> statuses);

    List<CodexTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

    /** 查询指定 Worker 下的活跃任务 */
    List<CodexTaskEntity> findByWorkerIdAndStatusIn(String workerId, List<String> statuses);
}

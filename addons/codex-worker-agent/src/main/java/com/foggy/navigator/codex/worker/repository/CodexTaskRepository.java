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

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndStatus(
            String codexThreadId, String workerId, String userId, String status);

    boolean existsByCodexThreadIdAndWorkerIdAndUserIdAndStatusIn(
            String codexThreadId, String workerId, String userId, List<String> statuses);

    Optional<CodexTaskEntity> findFirstByCodexThreadIdAndWorkerIdAndUserIdOrderByCreatedAtDesc(
            String codexThreadId, String workerId, String userId);

    List<CodexTaskEntity> findBySessionId(String sessionId);

    Optional<CodexTaskEntity> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<CodexTaskEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Page<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<CodexTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<CodexTaskEntity> findByStatusIn(List<String> statuses);

    List<CodexTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

    /** 查询指定 Worker 下的活跃任务 */
    List<CodexTaskEntity> findByWorkerIdAndStatusIn(String workerId, List<String> statuses);
}

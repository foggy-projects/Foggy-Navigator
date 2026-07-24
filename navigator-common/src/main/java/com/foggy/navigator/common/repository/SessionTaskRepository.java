package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionTaskRepository extends JpaRepository<SessionTaskEntity, Long> {

    Optional<SessionTaskEntity> findByTaskId(String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT task FROM SessionTaskEntity task WHERE task.taskId = :taskId")
    Optional<SessionTaskEntity> findByTaskIdForUpdate(@Param("taskId") String taskId);

    Optional<SessionTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    Optional<SessionTaskEntity> findByTaskIdAndUserIdAndTenantId(String taskId, String userId, String tenantId);

    @Query("SELECT task FROM SessionTaskEntity task " +
           "WHERE task.taskId = :taskId " +
           "AND task.userId = :userId " +
           "AND (task.tenantId IS NULL OR TRIM(task.tenantId) = '')")
    Optional<SessionTaskEntity> findTenantlessByTaskIdAndUserId(@Param("taskId") String taskId,
                                                                @Param("userId") String userId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionTaskEntity> findBySessionIdAndUserIdOrderByCreatedAtDesc(String sessionId, String userId);

    List<SessionTaskEntity> findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(
            String sessionId, String userId, String tenantId);

    Optional<SessionTaskEntity> findFirstBySessionIdAndUserIdOrderByCreatedAtDesc(String sessionId, String userId);

    List<SessionTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<SessionTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<SessionTaskEntity> findByWorkerIdAndUserIdOrderByCreatedAtDesc(String workerId, String userId);

    List<SessionTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, Collection<String> statuses);

    long countByTenantIdAndWorkerIdAndStatusIn(
            String tenantId,
            String workerId,
            Collection<String> statuses);

    List<SessionTaskEntity> findBySessionIdAndUserIdAndProviderTypeAndStatusInOrderByCreatedAtDesc(
            String sessionId,
            String userId,
            String providerType,
            Collection<String> statuses);

    /** 批量按 sessionId 查询任务（用于 N+1 消除） */
    List<SessionTaskEntity> findBySessionIdInOrderByCreatedAtDesc(Collection<String> sessionIds);

    /** 批量按 sessionId 和 userId 查询任务（用于会话组元数据操作校验） */
    List<SessionTaskEntity> findBySessionIdInAndUserIdOrderByCreatedAtDesc(Collection<String> sessionIds, String userId);

    /** 批量按 taskId 查询任务（用于历史消息补齐所属任务状态） */
    List<SessionTaskEntity> findByTaskIdIn(Collection<String> taskIds);
}

package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionTaskRepository extends JpaRepository<SessionTaskEntity, Long> {

    Optional<SessionTaskEntity> findByTaskId(String taskId);

    Optional<SessionTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<SessionTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<SessionTaskEntity> findByWorkerIdAndUserIdOrderByCreatedAtDesc(String workerId, String userId);

    List<SessionTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, Collection<String> statuses);

    /** 批量按 sessionId 查询任务（用于 N+1 消除） */
    List<SessionTaskEntity> findBySessionIdInOrderByCreatedAtDesc(Collection<String> sessionIds);
}

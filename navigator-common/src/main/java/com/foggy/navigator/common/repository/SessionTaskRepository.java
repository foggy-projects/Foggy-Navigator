package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionTaskRepository extends JpaRepository<SessionTaskEntity, Long> {

    Optional<SessionTaskEntity> findByTaskId(String taskId);

    Optional<SessionTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionTaskEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<SessionTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<SessionTaskEntity> findByWorkerIdAndUserIdOrderByCreatedAtDesc(String workerId, String userId);

    List<SessionTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, Collection<String> statuses);
}

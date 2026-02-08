package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaudeTaskRepository extends JpaRepository<ClaudeTaskEntity, Long> {

    Optional<ClaudeTaskEntity> findByTaskId(String taskId);

    Optional<ClaudeTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    List<ClaudeTaskEntity> findBySessionId(String sessionId);

    List<ClaudeTaskEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<ClaudeTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ClaudeTaskEntity> findByStatusIn(List<String> statuses);
}

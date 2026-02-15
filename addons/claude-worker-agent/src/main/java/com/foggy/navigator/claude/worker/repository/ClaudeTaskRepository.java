package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

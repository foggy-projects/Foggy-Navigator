package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CodexTaskRepository extends JpaRepository<CodexTaskEntity, Long> {

    Optional<CodexTaskEntity> findByTaskId(String taskId);

    Optional<CodexTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    List<CodexTaskEntity> findBySessionId(String sessionId);

    List<CodexTaskEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Page<CodexTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<CodexTaskEntity> findByDirectoryIdAndUserIdOrderByCreatedAtDesc(String directoryId, String userId);

    List<CodexTaskEntity> findByStatusIn(List<String> statuses);

    List<CodexTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

    /** 查询指定 Worker 下的活跃任务 */
    List<CodexTaskEntity> findByWorkerIdAndStatusIn(String workerId, List<String> statuses);
}

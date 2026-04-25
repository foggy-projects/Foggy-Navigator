package com.foggy.navigator.gemini.worker.repository;

import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeminiTaskRepository extends JpaRepository<GeminiTaskEntity, Long> {

    Optional<GeminiTaskEntity> findByTaskId(String taskId);

    Optional<GeminiTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    boolean existsByGeminiSessionIdAndWorkerIdAndUserId(String geminiSessionId, String workerId, String userId);

    boolean existsByGeminiSessionIdAndWorkerIdAndUserIdAndStatus(
            String geminiSessionId, String workerId, String userId, String status);

    List<GeminiTaskEntity> findBySessionId(String sessionId);

    List<GeminiTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

    List<GeminiTaskEntity> findByStatusIn(List<String> statuses);
}

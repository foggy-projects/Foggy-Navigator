package com.foggy.navigator.langgraph.worker.repository;

import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LanggraphTaskRepository extends JpaRepository<LanggraphTaskEntity, Long> {

    Optional<LanggraphTaskEntity> findByTaskId(String taskId);

    Optional<LanggraphTaskEntity> findByTaskIdAndUserId(String taskId, String userId);

    List<LanggraphTaskEntity> findBySessionId(String sessionId);

    List<LanggraphTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);

    List<LanggraphTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}

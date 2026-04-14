package com.foggy.navigator.langgraph.worker.repository;

import com.foggy.navigator.langgraph.worker.model.entity.LanggraphApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LanggraphApprovalRepository extends JpaRepository<LanggraphApprovalEntity, Long> {

    List<LanggraphApprovalEntity> findByTaskId(String taskId);

    Optional<LanggraphApprovalEntity> findByTaskIdAndStatus(String taskId, String status);

    List<LanggraphApprovalEntity> findBySessionId(String sessionId);
}

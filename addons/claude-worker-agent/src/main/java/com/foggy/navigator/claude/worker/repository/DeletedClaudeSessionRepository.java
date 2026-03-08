package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.DeletedClaudeSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeletedClaudeSessionRepository extends JpaRepository<DeletedClaudeSessionEntity, Long> {

    List<DeletedClaudeSessionEntity> findByWorkerIdAndUserId(String workerId, String userId);

    boolean existsByClaudeSessionIdAndWorkerIdAndUserId(String claudeSessionId, String workerId, String userId);
}

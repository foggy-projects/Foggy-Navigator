package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaudeWorkerRepository extends JpaRepository<ClaudeWorkerEntity, Long> {

    List<ClaudeWorkerEntity> findByUserId(String userId);

    Optional<ClaudeWorkerEntity> findByWorkerId(String workerId);

    Optional<ClaudeWorkerEntity> findByWorkerIdAndUserId(String workerId, String userId);

    void deleteByWorkerIdAndUserId(String workerId, String userId);
}

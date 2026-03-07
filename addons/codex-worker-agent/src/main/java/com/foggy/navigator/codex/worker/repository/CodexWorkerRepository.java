package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.codex.worker.model.entity.CodexWorkerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CodexWorkerRepository extends JpaRepository<CodexWorkerEntity, Long> {

    List<CodexWorkerEntity> findByUserId(String userId);

    Optional<CodexWorkerEntity> findByWorkerId(String workerId);

    Optional<CodexWorkerEntity> findByWorkerIdAndUserId(String workerId, String userId);

    void deleteByWorkerIdAndUserId(String workerId, String userId);
}

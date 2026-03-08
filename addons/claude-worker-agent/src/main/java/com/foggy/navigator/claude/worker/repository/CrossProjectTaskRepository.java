package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.CrossProjectTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CrossProjectTaskRepository extends JpaRepository<CrossProjectTaskEntity, Long> {

    Optional<CrossProjectTaskEntity> findByContextId(String contextId);

    Optional<CrossProjectTaskEntity> findByContextIdAndUserId(String contextId, String userId);

    Page<CrossProjectTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}

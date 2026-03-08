package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.CrossProjectPhaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrossProjectPhaseRepository extends JpaRepository<CrossProjectPhaseEntity, Long> {

    List<CrossProjectPhaseEntity> findByContextIdOrderByPhaseIndexAsc(String contextId);

    Optional<CrossProjectPhaseEntity> findByPhaseId(String phaseId);

    Optional<CrossProjectPhaseEntity> findByClaudeTaskId(String claudeTaskId);

    Optional<CrossProjectPhaseEntity> findByContextIdAndPhaseIndex(String contextId, int phaseIndex);
}

package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionRelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SessionRelationRepository extends JpaRepository<SessionRelationEntity, Long> {

    boolean existsByUserIdAndRelationTypeAndSourceSessionIdAndTargetSessionId(
            String userId,
            String relationType,
            String sourceSessionId,
            String targetSessionId
    );

    Optional<SessionRelationEntity> findFirstByUserIdAndRelationTypeAndTargetSessionIdOrderByCreatedAtDesc(
            String userId,
            String relationType,
            String targetSessionId
    );
}

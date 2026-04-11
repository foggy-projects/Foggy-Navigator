package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionRelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRelationRepository extends JpaRepository<SessionRelationEntity, Long> {

    boolean existsByUserIdAndRelationTypeAndSourceSessionIdAndTargetSessionId(
            String userId,
            String relationType,
            String sourceSessionId,
            String targetSessionId
    );
}

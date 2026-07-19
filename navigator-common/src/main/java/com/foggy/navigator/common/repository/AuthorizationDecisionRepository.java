package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Deliberately narrow append/query surface for redacted decision audit facts.
 * It intentionally does not extend CrudRepository/JpaRepository, so callers
 * receive no generic update or delete API.
 */
public interface AuthorizationDecisionRepository extends Repository<AuthorizationDecisionEntity, String>,
        AuthorizationDecisionRepositoryCustom {

    Optional<AuthorizationDecisionEntity> findByDecisionId(String decisionId);

    List<AuthorizationDecisionEntity> findByCorrelationIdOrderByEvaluatedAtAsc(String correlationId);

    List<AuthorizationDecisionEntity> findByPrincipalTypeAndPrincipalFingerprintOrderByEvaluatedAtDesc(
            String principalType,
            String principalFingerprint);

    List<AuthorizationDecisionEntity> findByActionIdAndRouteIdOrderByEvaluatedAtDesc(String actionId, String routeId);

    List<AuthorizationDecisionEntity> findByDecisionAndReasonCodeAndEvaluatedAtBetweenOrderByEvaluatedAtDesc(
            String decision,
            String reasonCode,
            LocalDateTime from,
            LocalDateTime to);
}

package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Common append/query contract for redacted canonical decision audit data.
 * It is intentionally not an HTTP surface and has no credential lifecycle API.
 */
public interface AuthorizationDecisionAuditStore {

    /**
     * Appends one canonical shadow result. The store, rather than the caller,
     * resolves the catalog route/action, recomputes the legacy/canonical diff,
     * and supplies the immutable server-owned deployment identity.
     */
    AuthorizationDecisionEntity appendShadow(
            AuthorizationContextV1 context,
            PolicyDecisionV1 canonicalDecision,
            LegacyEnforcementOutcome legacyOutcome,
            int httpStatus);

    Optional<AuthorizationDecisionEntity> findByDecisionId(String decisionId);

    List<AuthorizationDecisionEntity> findByCorrelationId(String correlationId);

    List<AuthorizationDecisionEntity> findByPrincipal(String principalType, String principalFingerprint);

    List<AuthorizationDecisionEntity> findByActionAndRoute(String actionId, String routeId);

    List<AuthorizationDecisionEntity> findByDecisionReasonAndEvaluatedBetween(
            String decision,
            String reasonCode,
            LocalDateTime from,
            LocalDateTime to);
}

package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import com.foggy.navigator.common.repository.AuthorizationDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Internal application adapter over the append-only decision repository. */
@Service
@RequiredArgsConstructor
class AuthorizationDecisionAuditStoreImpl implements AuthorizationDecisionAuditStore {

    private final AuthorizationDecisionRepository repository;
    private final DeploymentIdentityProvider deploymentIdentityProvider;
    private final AuthorizationRouteCatalog routeCatalog;

    @Override
    public AuthorizationDecisionEntity appendShadow(AuthorizationContextV1 context,
                                                     PolicyDecisionV1 canonicalDecision,
                                                     LegacyEnforcementOutcome legacyOutcome,
                                                     int httpStatus) {
        AuthorizationRouteManifestEntry registeredRoute = registeredRoute(context);
        AuthorizationDecisionAuditDraft decision = AuthorizationDecisionAuditDraft.fromShadow(
                context,
                canonicalDecision,
                legacyOutcome,
                httpStatus,
                registeredRoute);
        decision.validate();
        DeploymentIdentity identity = deploymentIdentityProvider.deploymentIdentity();
        return repository.append(AuthorizationDecisionEntity.fromAuditDraft(decision, identity));
    }

    private AuthorizationRouteManifestEntry registeredRoute(AuthorizationContextV1 context) {
        if (context == null || context.route() == null || context.route().routeId() == null) {
            return null;
        }
        return routeCatalog.findByRouteId(context.route().routeId()).orElse(null);
    }

    @Override
    public Optional<AuthorizationDecisionEntity> findByDecisionId(String decisionId) {
        return repository.findByDecisionId(decisionId);
    }

    @Override
    public List<AuthorizationDecisionEntity> findByCorrelationId(String correlationId) {
        return repository.findByCorrelationIdOrderByEvaluatedAtAsc(correlationId);
    }

    @Override
    public List<AuthorizationDecisionEntity> findByPrincipal(String principalType, String principalFingerprint) {
        return repository.findByPrincipalTypeAndPrincipalFingerprintOrderByEvaluatedAtDesc(
                principalType,
                principalFingerprint);
    }

    @Override
    public List<AuthorizationDecisionEntity> findByActionAndRoute(String actionId, String routeId) {
        return repository.findByActionIdAndRouteIdOrderByEvaluatedAtDesc(actionId, routeId);
    }

    @Override
    public List<AuthorizationDecisionEntity> findByDecisionReasonAndEvaluatedBetween(
            String decision,
            String reasonCode,
            LocalDateTime from,
            LocalDateTime to) {
        return repository.findByDecisionAndReasonCodeAndEvaluatedAtBetweenOrderByEvaluatedAtDesc(
                decision,
                reasonCode,
                from,
                to);
    }
}

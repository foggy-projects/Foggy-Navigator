package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses persist rather than merge so the only write operation exposed by the
 * decision repository cannot turn an existing decision into an update.
 */
public class AuthorizationDecisionRepositoryImpl implements AuthorizationDecisionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public AuthorizationDecisionEntity append(AuthorizationDecisionEntity decision) {
        if (decision == null || decision.getDecisionId() == null || decision.getDecisionId().isBlank()) {
            throw new IllegalArgumentException("decisionId is required for authorization decision audit append");
        }
        if (entityManager.find(AuthorizationDecisionEntity.class, decision.getDecisionId()) != null) {
            throw new IllegalStateException("authorization decision already exists");
        }
        entityManager.persist(decision);
        return decision;
    }
}

package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Insert-only persistence seam for a server-assigned forward target Session.
 */
@Repository
public class SessionForwardTargetSessionReservationRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public void insertAndFlush(SessionEntity session) {
        entityManager.persist(session);
        entityManager.flush();
    }
}

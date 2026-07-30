package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LifecycleEffectOutboxRepository
        extends JpaRepository<LifecycleEffectOutboxEntity, String> {
    long countByEffectStateNot(String effectState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LifecycleEffectOutboxEntity e where e.effectId = :effectId")
    Optional<LifecycleEffectOutboxEntity> findForUpdate(String effectId);
}

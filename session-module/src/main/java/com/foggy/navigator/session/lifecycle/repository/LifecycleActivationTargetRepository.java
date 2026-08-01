package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleActivationTargetEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LifecycleActivationTargetRepository
        extends JpaRepository<LifecycleActivationTargetEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LifecycleActivationTargetEntity t where t.targetId = :targetId")
    Optional<LifecycleActivationTargetEntity> findForUpdate(String targetId);
}

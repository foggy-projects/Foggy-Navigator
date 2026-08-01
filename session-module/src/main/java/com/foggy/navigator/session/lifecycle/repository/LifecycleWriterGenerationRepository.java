package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterGenerationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LifecycleWriterGenerationRepository
        extends JpaRepository<LifecycleWriterGenerationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from LifecycleWriterGenerationEntity g where g.generationId = :generationId")
    Optional<LifecycleWriterGenerationEntity> findForUpdate(String generationId);

    Optional<LifecycleWriterGenerationEntity> findByActiveSlot(String activeSlot);
}

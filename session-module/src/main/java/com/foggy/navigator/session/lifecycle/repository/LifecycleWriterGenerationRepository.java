package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterGenerationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifecycleWriterGenerationRepository
        extends JpaRepository<LifecycleWriterGenerationEntity, String> {
}

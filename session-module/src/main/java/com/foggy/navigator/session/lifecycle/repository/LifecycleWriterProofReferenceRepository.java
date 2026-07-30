package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifecycleWriterProofReferenceRepository
        extends JpaRepository<LifecycleWriterProofReferenceEntity, String> {
    long countByProofIdAndReleasedAtIsNull(String proofId);
}

package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;

public interface LifecycleWriterProofRepository
        extends JpaRepository<LifecycleWriterProofEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LifecycleWriterProofEntity p where p.proofId = :proofId")
    Optional<LifecycleWriterProofEntity> findForUpdate(String proofId);

    List<LifecycleWriterProofEntity> findByGenerationIdAndStatus(
            String generationId, String status);

    List<LifecycleWriterProofEntity>
    findTop10ByStatusOrderByProofIdAsc(String status);
}

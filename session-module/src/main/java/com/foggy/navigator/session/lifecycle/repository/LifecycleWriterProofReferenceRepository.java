package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.List;

public interface LifecycleWriterProofReferenceRepository
        extends JpaRepository<LifecycleWriterProofReferenceEntity, String> {
    long countByProofIdAndReleasedAtIsNull(String proofId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reference from LifecycleWriterProofReferenceEntity reference "
            + "where reference.referenceId = :referenceId")
    Optional<LifecycleWriterProofReferenceEntity> findForUpdate(String referenceId);

    List<LifecycleWriterProofReferenceEntity>
    findByProofIdAndReleasedAtIsNullOrderByAggregateTypeAscAggregateIdAsc(
            String proofId);

    List<LifecycleWriterProofReferenceEntity>
    findByAggregateTypeAndAggregateIdAndReleasedAtIsNull(
            String aggregateType, String aggregateId);
}

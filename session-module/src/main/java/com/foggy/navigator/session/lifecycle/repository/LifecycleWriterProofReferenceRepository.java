package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;

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

    @Query("select reference from LifecycleWriterProofReferenceEntity reference "
            + "where reference.proofId = :proofId "
            + "and reference.releasedAt is null "
            + "and (:cursor is null or reference.referenceId > :cursor) "
            + "order by reference.referenceId asc")
    List<LifecycleWriterProofReferenceEntity>
    findQuarantineBatch(
            @Param("proofId") String proofId,
            @Param("cursor") String cursor,
            Pageable pageable);

    List<LifecycleWriterProofReferenceEntity>
    findByAggregateTypeAndAggregateIdAndReleasedAtIsNull(
            String aggregateType, String aggregateId);
}

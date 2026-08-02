package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface LifecycleEffectOutboxRepository
        extends JpaRepository<LifecycleEffectOutboxEntity, String> {
    long countByEffectStateNot(String effectState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LifecycleEffectOutboxEntity e where e.effectId = :effectId")
    Optional<LifecycleEffectOutboxEntity> findForUpdate(String effectId);

    Optional<LifecycleEffectOutboxEntity> findByIdempotencyKey(String idempotencyKey);

    List<LifecycleEffectOutboxEntity> findByAggregateIdAndOperationId(
            String aggregateId, String operationId);

    List<LifecycleEffectOutboxEntity> findByAggregateId(String aggregateId);

    List<LifecycleEffectOutboxEntity>
    findTop100ByEffectTypeAndEffectStateOrderByCreatedAtAsc(
            String effectType, String effectState);

    @Query("select count(e) from LifecycleEffectOutboxEntity e "
            + "where e.proofId = :proofId "
            + "and e.effectState not in ('RESULT_OBSERVED','COMPLETED','REJECTED')")
    long countUnfinishedByProofId(@Param("proofId") String proofId);

    @Query("select count(e) from LifecycleEffectOutboxEntity e "
            + "where e.aggregateReferenceId = :referenceId "
            + "and e.effectState not in ('RESULT_OBSERVED','COMPLETED','REJECTED')")
    long countUnfinishedByReferenceId(@Param("referenceId") String referenceId);
}

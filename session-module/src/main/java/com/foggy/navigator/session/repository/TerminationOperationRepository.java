package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.TerminationOperationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TerminationOperationRepository extends JpaRepository<TerminationOperationEntity, String> {

    /**
     * Serializes lifecycle transitions for one durable operation. A verified
     * terminal event may race a cancellation HTTP response; the later response
     * must not overwrite the observed terminal evidence.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from TerminationOperationEntity operation where operation.operationId = :operationId")
    Optional<TerminationOperationEntity> findByOperationIdForUpdate(@Param("operationId") String operationId);

    List<TerminationOperationEntity> findByTaskIdAndOwnerUserIdAndTenantIdOrderByCreatedAtDesc(
            String taskId, String ownerUserId, String tenantId);

    List<TerminationOperationEntity> findByTaskIdAndOwnerUserIdAndTenantIdIsNullOrderByCreatedAtDesc(
            String taskId, String ownerUserId);

    List<TerminationOperationEntity> findByTaskIdOrderByCreatedAtDesc(String taskId);

    List<TerminationOperationEntity> findByTaskIdAndStatusNotInOrderByCreatedAtDesc(
            String taskId, List<String> terminalStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from TerminationOperationEntity operation where operation.taskId = :taskId order by operation.createdAt desc")
    List<TerminationOperationEntity> findByTaskIdOrderByCreatedAtDescForUpdate(@Param("taskId") String taskId);
}

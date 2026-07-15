package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface BusinessTaskTerminalStateRepository
        extends JpaRepository<BusinessTaskTerminalStateEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from BusinessTaskTerminalStateEntity terminal " +
            "where terminal.tenantId = :tenantId and terminal.workerTaskId = :workerTaskId")
    Optional<BusinessTaskTerminalStateEntity> findByTenantIdAndWorkerTaskIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("workerTaskId") String workerTaskId);

    Optional<BusinessTaskTerminalStateEntity> findByTenantIdAndWorkerTaskId(
            String tenantId, String workerTaskId);

    boolean existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
            String tenantId, String workerTaskId, LocalDateTime now);

    boolean existsByTenantIdAndBusinessTaskIdAndExpiresAtAfter(
            String tenantId, String businessTaskId, LocalDateTime now);
}

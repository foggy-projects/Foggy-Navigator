package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupRepairEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskTerminalCleanupRepairRepository
        extends JpaRepository<TaskTerminalCleanupRepairEntity, String> {

    /**
     * A repair request id has one durable task binding. The locking lookup
     * serializes competing commands before either can create its receipt;
     * the schema unique key remains the durable cross-process backstop.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select repair from TaskTerminalCleanupRepairEntity repair "
            + "where repair.clientRequestId = :clientRequestId")
    Optional<TaskTerminalCleanupRepairEntity> findByClientRequestIdForUpdate(
            @Param("clientRequestId") String clientRequestId);
}

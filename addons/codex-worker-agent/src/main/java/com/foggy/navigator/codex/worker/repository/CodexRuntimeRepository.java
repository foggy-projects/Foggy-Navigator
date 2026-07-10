package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface CodexRuntimeRepository extends JpaRepository<CodexRuntimeEntity, Long> {

    Optional<CodexRuntimeEntity> findByRuntimeIdAndRevision(String runtimeId, Integer revision);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select runtime from CodexRuntimeEntity runtime "
            + "where runtime.runtimeId = :runtimeId and runtime.revision = :revision")
    Optional<CodexRuntimeEntity> findByRuntimeIdAndRevisionForUpdate(
            @Param("runtimeId") String runtimeId,
            @Param("revision") Integer revision);

    List<CodexRuntimeEntity> findByRuntimeIdOrderByRevisionDesc(String runtimeId);

    List<CodexRuntimeEntity> findByWorkerIdOrderByPriorityDescRevisionDesc(String workerId);

    List<CodexRuntimeEntity> findByEnabledTrueOrderByUpdatedAtAsc();

    List<CodexRuntimeEntity> findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
            String workerId, String runtimeType);

    @Query("select coalesce(max(runtime.revision), 0) from CodexRuntimeEntity runtime "
            + "where runtime.runtimeId = :runtimeId")
    Integer findMaxRevision(@Param("runtimeId") String runtimeId);
}

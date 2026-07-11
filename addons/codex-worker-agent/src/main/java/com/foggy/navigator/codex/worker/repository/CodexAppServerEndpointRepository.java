package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CodexAppServerEndpointRepository
        extends JpaRepository<CodexAppServerEndpointEntity, Long> {

    Optional<CodexAppServerEndpointEntity> findByEndpointId(String endpointId);

    List<CodexAppServerEndpointEntity> findByWorkerIdOrderByUpdatedAtDesc(String workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select endpoint from CodexAppServerEndpointEntity endpoint "
            + "where endpoint.endpointId = :endpointId")
    Optional<CodexAppServerEndpointEntity> findByEndpointIdForUpdate(
            @Param("endpointId") String endpointId);
}

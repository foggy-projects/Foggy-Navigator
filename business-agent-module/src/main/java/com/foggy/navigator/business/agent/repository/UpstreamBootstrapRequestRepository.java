package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UpstreamBootstrapRequestRepository extends JpaRepository<UpstreamBootstrapRequestEntity, Long> {

    Optional<UpstreamBootstrapRequestEntity> findByRequestCodeHash(String requestCodeHash);

    Optional<UpstreamBootstrapRequestEntity> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from UpstreamBootstrapRequestEntity r where r.requestCodeHash = :requestCodeHash")
    Optional<UpstreamBootstrapRequestEntity> findByRequestCodeHashForUpdate(@Param("requestCodeHash") String requestCodeHash);

    List<UpstreamBootstrapRequestEntity> findTop100ByOrderByCreatedAtDesc();

    List<UpstreamBootstrapRequestEntity> findTop100ByRequestedTenantIdOrderByCreatedAtDesc(String requestedTenantId);
}

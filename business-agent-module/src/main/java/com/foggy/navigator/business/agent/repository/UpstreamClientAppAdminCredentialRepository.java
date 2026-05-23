package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UpstreamClientAppAdminCredentialRepository extends JpaRepository<UpstreamClientAppAdminCredentialEntity, Long> {

    Optional<UpstreamClientAppAdminCredentialEntity> findByCredentialKeyHash(String credentialKeyHash);

    Optional<UpstreamClientAppAdminCredentialEntity> findByCredentialId(String credentialId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from UpstreamClientAppAdminCredentialEntity c where c.credentialId = :credentialId")
    Optional<UpstreamClientAppAdminCredentialEntity> findByCredentialIdForUpdate(@Param("credentialId") String credentialId);
}

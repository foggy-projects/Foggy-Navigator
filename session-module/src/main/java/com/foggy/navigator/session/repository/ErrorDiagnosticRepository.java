package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.ErrorDiagnosticEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ErrorDiagnosticRepository extends JpaRepository<ErrorDiagnosticEntity, String> {
    Optional<ErrorDiagnosticEntity> findFirstByTaskIdOrderByCreatedAtDesc(String taskId);
    Optional<ErrorDiagnosticEntity> findByDiagnosticIdAndOwnerUserIdAndTenantId(
            String diagnosticId, String ownerUserId, String tenantId);
    Optional<ErrorDiagnosticEntity> findByDiagnosticIdAndOwnerUserIdAndTenantIdIsNull(
            String diagnosticId, String ownerUserId);
    long deleteByExpiresAtBefore(LocalDateTime expiresAt);
}

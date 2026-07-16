package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.ErrorDiagnosticShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ErrorDiagnosticShareRepository extends JpaRepository<ErrorDiagnosticShareEntity, String> {
    Optional<ErrorDiagnosticShareEntity> findByTokenHash(String tokenHash);
    List<ErrorDiagnosticShareEntity> findByDiagnosticIdOrderByCreatedAtDesc(String diagnosticId);
    long deleteByExpiresAtBefore(LocalDateTime expiresAt);

    @Modifying
    @Query("UPDATE ErrorDiagnosticShareEntity share SET share.accessCount = share.accessCount + 1, "
            + "share.lastAccessAt = :accessedAt WHERE share.shareId = :shareId")
    int recordAccess(@Param("shareId") String shareId, @Param("accessedAt") LocalDateTime accessedAt);
}

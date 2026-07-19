package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Authority record for the server-resolved upstream owner of one Navigator
 * tenant. ClientApp counts, aliases and request data are deliberately absent.
 */
@Getter
@Setter
@Entity
@Table(name = "authorization_tenant_authority", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_tenant_authority_scope", columnNames = {
                "navigator_instance_id", "tenant_id"
        })
}, indexes = {
        @Index(name = "idx_auth_tenant_authority_instance_upstream_status", columnList = "navigator_instance_id,upstream_system_id,status"),
        @Index(name = "idx_auth_tenant_authority_upstream_status", columnList = "upstream_system_id,status")
})
public class AuthorizationTenantAuthorityEntity {

    @Id
    @Column(name = "tenant_authority_id", length = 64)
    private String tenantAuthorityId;

    @Column(name = "navigator_instance_id", length = 64, nullable = false)
    private String navigatorInstanceId;

    @Column(name = "environment_profile", length = 32, nullable = false)
    private String environmentProfile;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "upstream_system_id", length = 128, nullable = false)
    private String upstreamSystemId;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "source_reference", length = 128, nullable = false)
    private String sourceReference;

    @Column(name = "migration_reference", length = 128)
    private String migrationReference;

    @Column(name = "resolved_at", nullable = false)
    private LocalDateTime resolvedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (resolvedAt == null) {
            resolvedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

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
 * Typed management principal persistence. This table intentionally has no
 * relationship mappings: the surrounding authorization context composes
 * opaque identifiers and keeps legacy identities outside this aggregate.
 */
@Getter
@Setter
@Entity
@Table(name = "authorization_principal", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_principal_scope", columnNames = {
                "navigator_instance_id", "principal_type", "principal_id"
        })
}, indexes = {
        @Index(name = "idx_auth_principal_instance_type_status", columnList = "navigator_instance_id,principal_type,status"),
        @Index(name = "idx_auth_principal_upstream_status", columnList = "source_upstream_system_id,status")
})
public class AuthorizationPrincipalEntity {

    /** Opaque storage identity; semantic principal identity is scope-qualified below. */
    @Id
    @Column(name = "principal_record_id", length = 64)
    private String principalRecordId;

    @Column(name = "navigator_instance_id", length = 64, nullable = false)
    private String navigatorInstanceId;

    @Column(name = "environment_profile", length = 32, nullable = false)
    private String environmentProfile;

    @Column(name = "principal_type", length = 64, nullable = false)
    private String principalType;

    @Column(name = "principal_id", length = 128, nullable = false)
    private String principalId;

    @Column(name = "source_upstream_system_id", length = 128)
    private String sourceUpstreamSystemId;

    /** Server-controlled trust ceiling; request data must never populate it. */
    @Column(name = "upstream_trust_profile", length = 64, nullable = false)
    private String upstreamTrustProfile;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

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

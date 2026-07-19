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
 * Versioned S2 platform grant metadata. It deliberately models the dynamic
 * server-owned tenant scope mode, not a caller-supplied tenant list.
 */
@Getter
@Setter
@Entity
@Table(name = "authorization_platform_grant", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_platform_grant_scope", columnNames = {
                "navigator_instance_id", "environment_profile", "principal_id", "upstream_system_id"
        })
}, indexes = {
        @Index(name = "idx_auth_platform_grant_instance_upstream_status", columnList = "navigator_instance_id,upstream_system_id,status"),
        @Index(name = "idx_auth_platform_grant_principal_status", columnList = "principal_id,status")
})
public class AuthorizationPlatformGrantEntity {

    @Id
    @Column(name = "platform_grant_id", length = 64)
    private String platformGrantId;

    @Column(name = "navigator_instance_id", length = 64, nullable = false)
    private String navigatorInstanceId;

    @Column(name = "environment_profile", length = 32, nullable = false)
    private String environmentProfile;

    /** Opaque foreign-key-like field; no JPA association is deliberately used. */
    @Column(name = "principal_record_id", length = 64, nullable = false)
    private String principalRecordId;

    @Column(name = "principal_id", length = 128, nullable = false)
    private String principalId;

    @Column(name = "upstream_system_id", length = 128, nullable = false)
    private String upstreamSystemId;

    /** For P1A/P1B this is the server-controlled value UPSTREAM_OWNED. */
    @Column(name = "tenant_scope_mode", length = 64, nullable = false)
    private String tenantScopeMode;

    @Column(name = "action_set_ref", length = 160, nullable = false)
    private String actionSetRef;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "approval_reference", length = 128)
    private String approvalReference;

    @Column(name = "source_reference", length = 128)
    private String sourceReference;

    /** Digest only; raw approval rationale is not stored in this aggregate. */
    @Column(name = "reason_digest", length = 64)
    private String reasonDigest;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

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
        if (issuedAt == null) {
            issuedAt = now;
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

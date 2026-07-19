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
 * Opaque management access/action token metadata. Runtime, task and Worker
 * tokens remain in their existing tables and are intentionally not represented
 * here.
 */
@Getter
@Setter
@Entity
@Table(name = "authorization_management_token", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_mgmt_token_hash", columnNames = "token_hash"),
        @UniqueConstraint(name = "uk_auth_mgmt_token_ref", columnNames = "token_reference"),
        @UniqueConstraint(name = "uk_auth_mgmt_security_nonce", columnNames = "security_action_nonce")
}, indexes = {
        @Index(name = "idx_auth_mgmt_token_credential_purpose_status_exp", columnList = "credential_id,purpose,status,expires_at"),
        @Index(name = "idx_auth_mgmt_token_instance_status", columnList = "navigator_instance_id,status")
})
public class AuthorizationManagementTokenEntity {

    @Id
    @Column(name = "token_id", length = 64)
    private String tokenId;

    /** One-way token verifier/hash; never expose or log it. */
    @Column(name = "token_hash", length = 128, nullable = false)
    private String tokenHash;

    /** Opaque external verifier reference; never a bearer value. */
    @Column(name = "token_reference", length = 192, nullable = false)
    private String tokenReference;

    @Column(name = "credential_id", length = 64, nullable = false)
    private String credentialId;

    @Column(name = "credential_generation", nullable = false)
    private Integer credentialGeneration;

    @Column(name = "navigator_instance_id", length = 64, nullable = false)
    private String navigatorInstanceId;

    @Column(name = "environment_profile", length = 32, nullable = false)
    private String environmentProfile;

    @Column(name = "audience", length = 160, nullable = false)
    private String audience;

    /** CONTROL_ACCESS or SECURITY_ACTION. */
    @Column(name = "purpose", length = 32, nullable = false)
    private String purpose;

    @Column(name = "action_id", length = 160)
    private String actionId;

    @Column(name = "target_digest", length = 64)
    private String targetDigest;

    @Column(name = "impact_digest", length = 64)
    private String impactDigest;

    @Column(name = "reason_digest", length = 64)
    private String reasonDigest;

    @Column(name = "approval_reference", length = 128)
    private String approvalReference;

    /** Unique when present; MySQL permits multiple NULL values for control-access tokens. */
    @Column(name = "security_action_nonce", length = 128)
    private String securityActionNonce;

    @Column(name = "platform_grant_id", length = 64)
    private String platformGrantId;

    @Column(name = "platform_grant_version")
    private Long platformGrantVersion;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

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

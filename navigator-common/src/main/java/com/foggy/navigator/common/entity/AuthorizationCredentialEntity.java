package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Metadata for a future typed management credential. It stores an opaque
 * verifier reference and fingerprint, never credential material itself.
 */
@Getter
@Setter
@Entity
@Table(name = "authorization_credential", indexes = {
        @Index(name = "idx_auth_credential_principal_lane_status_exp", columnList = "principal_id,credential_lane,status,expires_at"),
        @Index(name = "idx_auth_credential_instance_status", columnList = "navigator_instance_id,status"),
        @Index(name = "idx_auth_credential_verifier_ref", columnList = "verifier_reference", unique = true)
})
public class AuthorizationCredentialEntity {

    @Id
    @Column(name = "credential_id", length = 64)
    private String credentialId;

    @Column(name = "navigator_instance_id", length = 64, nullable = false)
    private String navigatorInstanceId;

    @Column(name = "environment_profile", length = 32, nullable = false)
    private String environmentProfile;

    /** Opaque foreign-key-like field; no JPA association is deliberately used. */
    @Column(name = "principal_record_id", length = 64, nullable = false)
    private String principalRecordId;

    @Column(name = "principal_id", length = 128, nullable = false)
    private String principalId;

    @Column(name = "principal_type", length = 64, nullable = false)
    private String principalType;

    @Column(name = "credential_lane", length = 64, nullable = false)
    private String credentialLane;

    /** Opaque verifier/KMS reference, not a raw secret or token. */
    @Column(name = "verifier_reference", length = 192, nullable = false)
    private String verifierReference;

    /** Safe display/audit fingerprint only. */
    @Column(name = "credential_fingerprint", length = 64, nullable = false)
    private String credentialFingerprint;

    @Column(name = "generation", nullable = false)
    private Integer generation;

    @Column(name = "action_set_ref", length = 160, nullable = false)
    private String actionSetRef;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "rotation_of_credential_id", length = 64)
    private String rotationOfCredentialId;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by_principal_id", length = 128)
    private String revokedByPrincipalId;

    /** Digest only; do not retain raw revocation reason material. */
    @Column(name = "revoke_reason_digest", length = 64)
    private String revokeReasonDigest;

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

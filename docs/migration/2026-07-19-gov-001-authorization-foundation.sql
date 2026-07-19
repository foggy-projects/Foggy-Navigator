-- GOV-001 P1A additive authorization foundation.
--
-- Apply this MySQL 8 schema before starting a production deployment with
-- spring.jpa.hibernate.ddl-auto=validate. It creates only new tables: no
-- legacy authorization, credential, runtime, task, Worker or application
-- table is altered, seeded, copied or backfilled by this migration.
--
-- The tables are intentionally empty after application. Typed principal,
-- credential, grant, authority and token issuance belongs to P1B and is not
-- authorized by this P1A migration.

CREATE TABLE IF NOT EXISTS authorization_principal (
    principal_record_id VARCHAR(64) NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    principal_type VARCHAR(64) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    source_upstream_system_id VARCHAR(128) NULL,
    upstream_trust_profile VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (principal_record_id),
    UNIQUE KEY uk_auth_principal_scope (navigator_instance_id, principal_type, principal_id),
    KEY idx_auth_principal_instance_type_status (navigator_instance_id, principal_type, status),
    KEY idx_auth_principal_upstream_status (source_upstream_system_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS authorization_credential (
    credential_id VARCHAR(64) NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    principal_record_id VARCHAR(64) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    principal_type VARCHAR(64) NOT NULL,
    credential_lane VARCHAR(64) NOT NULL,
    verifier_reference VARCHAR(192) NOT NULL,
    credential_fingerprint VARCHAR(64) NOT NULL,
    generation INT NOT NULL,
    action_set_ref VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    rotated_at DATETIME(6) NULL,
    rotation_of_credential_id VARCHAR(64) NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_principal_id VARCHAR(128) NULL,
    revoke_reason_digest VARCHAR(64) NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (credential_id),
    KEY idx_auth_credential_principal_lane_status_exp (principal_id, credential_lane, status, expires_at),
    KEY idx_auth_credential_instance_status (navigator_instance_id, status),
    UNIQUE KEY idx_auth_credential_verifier_ref (verifier_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS authorization_management_token (
    token_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    token_reference VARCHAR(192) NOT NULL,
    credential_id VARCHAR(64) NOT NULL,
    credential_generation INT NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    audience VARCHAR(160) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    action_id VARCHAR(160) NULL,
    target_digest VARCHAR(64) NULL,
    impact_digest VARCHAR(64) NULL,
    reason_digest VARCHAR(64) NULL,
    approval_reference VARCHAR(128) NULL,
    security_action_nonce VARCHAR(128) NULL,
    platform_grant_id VARCHAR(64) NULL,
    platform_grant_version BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_id),
    UNIQUE KEY uk_auth_mgmt_token_hash (token_hash),
    UNIQUE KEY uk_auth_mgmt_token_ref (token_reference),
    UNIQUE KEY uk_auth_mgmt_security_nonce (security_action_nonce),
    KEY idx_auth_mgmt_token_credential_purpose_status_exp (credential_id, purpose, status, expires_at),
    KEY idx_auth_mgmt_token_instance_status (navigator_instance_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS authorization_platform_grant (
    platform_grant_id VARCHAR(64) NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    principal_record_id VARCHAR(64) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    upstream_system_id VARCHAR(128) NOT NULL,
    tenant_scope_mode VARCHAR(64) NOT NULL,
    action_set_ref VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    approval_reference VARCHAR(128) NULL,
    source_reference VARCHAR(128) NULL,
    reason_digest VARCHAR(64) NULL,
    issued_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (platform_grant_id),
    UNIQUE KEY uk_auth_platform_grant_scope (navigator_instance_id, environment_profile, principal_id, upstream_system_id),
    KEY idx_auth_platform_grant_instance_upstream_status (navigator_instance_id, upstream_system_id, status),
    KEY idx_auth_platform_grant_principal_status (principal_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS authorization_tenant_authority (
    tenant_authority_id VARCHAR(64) NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    upstream_system_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    migration_reference VARCHAR(128) NULL,
    resolved_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_authority_id),
    UNIQUE KEY uk_auth_tenant_authority_scope (navigator_instance_id, tenant_id),
    KEY idx_auth_tenant_authority_instance_upstream_status (navigator_instance_id, upstream_system_id, status),
    KEY idx_auth_tenant_authority_upstream_status (upstream_system_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- This is append-only application audit evidence. It stores only stable
-- identifiers, reason codes and redacted fingerprints/digests; do not add
-- bearer tokens, credential material, raw request bodies, account data or
-- upstreamUserToken to this table.
CREATE TABLE IF NOT EXISTS authorization_decision (
    decision_id VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    action_catalog_version VARCHAR(64) NOT NULL,
    server_build VARCHAR(128) NOT NULL,
    navigator_instance_id VARCHAR(64) NOT NULL,
    environment_profile VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    evaluation_mode VARCHAR(32) NOT NULL,
    principal_type VARCHAR(64) NOT NULL,
    principal_fingerprint VARCHAR(64) NULL,
    credential_lane VARCHAR(64) NULL,
    credential_fingerprint VARCHAR(64) NULL,
    action_id VARCHAR(160) NOT NULL,
    target_type VARCHAR(160) NULL,
    target_fingerprint VARCHAR(64) NULL,
    route_id VARCHAR(192) NOT NULL,
    request_digest VARCHAR(64) NULL,
    impact_digest VARCHAR(64) NULL,
    decision VARCHAR(16) NOT NULL,
    reason_code VARCHAR(160) NOT NULL,
    legacy_decision VARCHAR(16) NULL,
    legacy_reason_code VARCHAR(160) NULL,
    diff_code VARCHAR(96) NULL,
    evaluated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (decision_id),
    KEY idx_auth_decision_correlation (correlation_id),
    KEY idx_auth_decision_principal (principal_type, principal_fingerprint),
    KEY idx_auth_decision_credential (credential_lane, credential_fingerprint),
    KEY idx_auth_decision_action (action_id),
    KEY idx_auth_decision_target (target_type, target_fingerprint),
    KEY idx_auth_decision_result_reason (decision, reason_code),
    KEY idx_auth_decision_evaluated_at (evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

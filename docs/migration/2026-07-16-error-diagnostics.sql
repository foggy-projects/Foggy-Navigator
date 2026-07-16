-- Redacted diagnostic snapshots and independently revocable temporary share tokens.
-- Apply before deploying production with spring.jpa.hibernate.ddl-auto=validate.
-- The share table stores SHA-256 token hashes only; plaintext bearer tokens are
-- returned once to the authenticated issuer and never persisted.

CREATE TABLE error_diagnostics (
    diagnostic_id VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    redaction_version INT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NULL,
    provider_type VARCHAR(32) NOT NULL,
    runtime_type VARCHAR(32) NULL,
    worker_label VARCHAR(128) NULL,
    error_code VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL,
    runtime_phase VARCHAR(48) NOT NULL,
    safe_message VARCHAR(512) NOT NULL,
    recoverable BIT NOT NULL,
    provider_status VARCHAR(160) NULL,
    http_status INT NULL,
    retry_count INT NULL,
    exception_type VARCHAR(160) NULL,
    diagnostic_text TEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (diagnostic_id),
    KEY idx_ed_task_id (task_id),
    KEY idx_ed_session_id (session_id),
    KEY idx_ed_owner_scope (owner_user_id, tenant_id),
    KEY idx_ed_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE error_diagnostic_shares (
    share_id VARCHAR(64) NOT NULL,
    diagnostic_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    last_access_at DATETIME(6) NULL,
    access_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (share_id),
    UNIQUE KEY idx_eds_token_hash (token_hash),
    KEY idx_eds_diagnostic_id (diagnostic_id),
    KEY idx_eds_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
